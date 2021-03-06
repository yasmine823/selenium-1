// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.docker;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Platform;
import org.openqa.selenium.docker.Docker;
import org.openqa.selenium.docker.DockerException;
import org.openqa.selenium.docker.Image;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.ConfigException;
import org.openqa.selenium.grid.node.SessionFactory;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import static org.openqa.selenium.Platform.WINDOWS;

public class DockerOptions {

  private static final String DOCKER_SECTION = "docker";

  private static final Logger LOG = Logger.getLogger(DockerOptions.class.getName());
  private static final Json JSON = new Json();
  private final Config config;

  public DockerOptions(Config config) {
    this.config = Require.nonNull("Config", config);
  }

  private URI getDockerUri() {
    try {
      Optional<String> possibleUri = config.get(DOCKER_SECTION, "url");
      if (possibleUri.isPresent()) {
        return new URI(possibleUri.get());
      }

      Optional<String> possibleHost = config.get(DOCKER_SECTION, "host");
      if (possibleHost.isPresent()) {
        String host = possibleHost.get();
        if (!(host.startsWith("tcp:") || host.startsWith("http:") || host.startsWith("https"))) {
          host = "http://" + host;
        }
        URI uri = new URI(host);
        return new URI(
          "http",
          uri.getUserInfo(),
          uri.getHost(),
          uri.getPort(),
          uri.getPath(),
          null,
          null);
      }

      // Default for the system we're running on.
      if (Platform.getCurrent().is(WINDOWS)) {
        return new URI("http://localhost:2376");
      }
      return new URI("unix:/var/run/docker.sock");
    } catch (URISyntaxException e) {
      throw new ConfigException("Unable to determine docker url", e);
    }
  }

  private boolean isEnabled(HttpClient.Factory clientFactory) {
    if (!config.getAll(DOCKER_SECTION, "configs").isPresent()) {
      return false;
    }

    // Is the daemon up and running?
    URI uri = getDockerUri();
    HttpClient client = clientFactory.createClient(ClientConfig.defaultConfig().baseUri(uri));

    return new Docker(client).isSupported();
  }

  public Map<Capabilities, Collection<SessionFactory>> getDockerSessionFactories(
    Tracer tracer,
    HttpClient.Factory clientFactory) {

    if (!isEnabled(clientFactory)) {
      return ImmutableMap.of();
    }

    List<String> allConfigs = config.getAll(DOCKER_SECTION, "configs")
        .orElseThrow(() -> new DockerException("Unable to find docker configs"));

    Multimap<String, Capabilities> kinds = HashMultimap.create();
    for (int i = 0; i < allConfigs.size(); i++) {
      String imageName = allConfigs.get(i);
      i++;
      if (i == allConfigs.size()) {
        throw new DockerException("Unable to find JSON config");
      }
      Capabilities stereotype = JSON.toType(allConfigs.get(i), Capabilities.class);

      kinds.put(imageName, stereotype);
    }

    HttpClient client = clientFactory.createClient(ClientConfig.defaultConfig().baseUri(getDockerUri()));
    Docker docker = new Docker(client);

    loadImages(docker, kinds.keySet().toArray(new String[0]));
    Image videoImage = getVideoImage(docker);
    if (isVideoRecordingAvailable()) {
      loadImages(docker, videoImage.getName());
    }

    int maxContainerCount = Runtime.getRuntime().availableProcessors();
    ImmutableMultimap.Builder<Capabilities, SessionFactory> factories = ImmutableMultimap.builder();
    kinds.forEach((name, caps) -> {
      Image image = docker.getImage(name);
      for (int i = 0; i < maxContainerCount; i++) {
        if (isVideoRecordingAvailable()) {
          factories.put(
            caps,
            new DockerSessionFactory(
              tracer,
              clientFactory,
              docker,
              getDockerUri(),
              image,
              caps,
              videoImage,
              getAssetsPath()));
        } else {
          factories.put(
            caps,
            new DockerSessionFactory(tracer, clientFactory, docker, getDockerUri(), image, caps));
        }
      }
      LOG.info(String.format(
          "Mapping %s to docker image %s %d times",
          caps,
          name,
          maxContainerCount));
    });
    return factories.build().asMap();
  }

  private boolean isVideoRecordingAvailable() {
    return config.get(DOCKER_SECTION, "video-image").isPresent()
           && config.get(DOCKER_SECTION, "assets-path").isPresent();
  }

  private Image getVideoImage(Docker docker) {
    Optional<String> videoImage = config.get(DOCKER_SECTION, "video-image");
    return videoImage.map(docker::getImage).orElse(null);
  }

  private String getAssetsPath() {
    return config.get(DOCKER_SECTION, "assets-path").orElse("");
  }

  private void loadImages(Docker docker, String... imageNames) {
    CompletableFuture<Void> cd = CompletableFuture.allOf(
        Arrays.stream(imageNames)
            .map(name -> CompletableFuture.supplyAsync(() -> docker.getImage(name)))
          .toArray(CompletableFuture[]::new));

    try {
      cd.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new RuntimeException(cause);
    }
  }
}
