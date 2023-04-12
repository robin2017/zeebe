/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway;

import io.atomix.cluster.AtomixCluster;
import io.atomix.cluster.ClusterMembershipEvent.Type;
import io.camunda.zeebe.gateway.impl.SpringGatewayBridge;
import io.camunda.zeebe.gateway.impl.broker.BrokerClient;
import io.camunda.zeebe.gateway.impl.broker.cluster.BrokerTopologyManager;
import io.camunda.zeebe.gateway.impl.configuration.GatewayCfg;
import io.camunda.zeebe.protocol.impl.encoding.BrokerInfo;
import io.camunda.zeebe.protocol.impl.record.JobActivationPropertiesImpl;
import io.camunda.zeebe.scheduler.ActorScheduler;
import io.camunda.zeebe.shared.Profile;
import io.camunda.zeebe.transport.stream.impl.ClientStreamService;
import io.camunda.zeebe.util.CloseableSilently;
import io.camunda.zeebe.util.VersionUtil;
import io.camunda.zeebe.util.error.FatalErrorHandler;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Entry point for the standalone gateway application. By default, it enables the {@link
 * Profile#GATEWAY} profile, loading the appropriate application properties overrides.
 *
 * <p>See {@link #main(String[])} for more.
 */
@SpringBootApplication(
    proxyBeanMethods = false,
    scanBasePackages = {
      "io.camunda.zeebe.gateway",
      "io.camunda.zeebe.shared",
      "io.camunda.zeebe.util.liveness"
    })
@ConfigurationPropertiesScan(basePackages = {"io.camunda.zeebe.gateway", "io.camunda.zeebe.shared"})
public class StandaloneGateway
    implements CommandLineRunner, ApplicationListener<ContextClosedEvent>, CloseableSilently {
  private static final Logger LOG = Loggers.GATEWAY_LOGGER;

  private final GatewayCfg configuration;
  private final SpringGatewayBridge springGatewayBridge;
  private final ActorScheduler actorScheduler;
  private final AtomixCluster atomixCluster;
  private final BrokerClient brokerClient;

  private Gateway gateway;

  @Autowired
  public StandaloneGateway(
      final GatewayCfg configuration,
      final SpringGatewayBridge springGatewayBridge,
      final ActorScheduler actorScheduler,
      final AtomixCluster atomixCluster,
      final BrokerClient brokerClient) {
    this.configuration = configuration;
    this.springGatewayBridge = springGatewayBridge;
    this.actorScheduler = actorScheduler;
    this.atomixCluster = atomixCluster;
    this.brokerClient = brokerClient;
  }

  public static void main(final String[] args) {
    Thread.setDefaultUncaughtExceptionHandler(
        FatalErrorHandler.uncaughtExceptionHandler(Loggers.GATEWAY_LOGGER));

    System.setProperty("spring.banner.location", "classpath:/assets/zeebe_gateway_banner.txt");
    final var application =
        new SpringApplicationBuilder(StandaloneGateway.class)
            .web(WebApplicationType.SERVLET)
            .logStartupInfo(true)
            .profiles(Profile.GATEWAY.getId())
            .build(args);

    application.run();
  }

  @Override
  public void run(final String... args) throws Exception {
    configuration.init();

    if (LOG.isInfoEnabled()) {
      LOG.info("Version: {}", VersionUtil.getVersion());
      LOG.info("Starting standalone gateway with configuration {}", configuration.toJson());
    }

    final var jobStreamServer =
        new ClientStreamService<JobActivationPropertiesImpl>(
            atomixCluster.getCommunicationService());

    gateway = new Gateway(configuration, brokerClient, actorScheduler);

    springGatewayBridge.registerBrokerClientSupplier(gateway::getBrokerClient);
    springGatewayBridge.registerGatewayStatusSupplier(gateway::getStatus);
    springGatewayBridge.registerClusterStateSupplier(
        () ->
            Optional.ofNullable(gateway.getBrokerClient())
                .map(BrokerClient::getTopologyManager)
                .map(BrokerTopologyManager::getTopology));

    actorScheduler.start();
    atomixCluster.start();
    brokerClient.start();
    actorScheduler.submitActor(jobStreamServer).join();
    gateway.start().join(30, TimeUnit.SECONDS);

    // TODO: implement as a topology listener
    atomixCluster
        .getMembershipService()
        .addListener(
            event -> {
              if (!(event.type() == Type.MEMBER_ADDED || event.type() == Type.MEMBER_REMOVED)) {
                return;
              }

              if (BrokerInfo.fromProperties(event.subject().properties()) == null) {
                return;
              }

              if (event.type() == Type.MEMBER_ADDED) {
                jobStreamServer.onServerJoined(event.subject().id());
              } else if (event.type() == Type.MEMBER_REMOVED) {
                jobStreamServer.onServerRemoved(event.subject().id());
              }
            });
    atomixCluster
        .getMembershipService()
        .getMembers()
        .forEach(
            member -> {
              if (member.equals(atomixCluster.getMembershipService().getLocalMember())) {
                return;
              }

              if (BrokerInfo.fromProperties(member.properties()) != null) {
                jobStreamServer.onServerJoined(member.id());
              }
            });
  }

  @Override
  public void onApplicationEvent(final ContextClosedEvent event) {
    close();
  }

  @Override
  public void close() {
    if (gateway != null) {
      try {
        gateway.stop();
      } catch (final Exception e) {
        LOG.warn("Failed to gracefully shutdown gRPC gateway", e);
      }
    }

    LogManager.shutdown();
  }
}
