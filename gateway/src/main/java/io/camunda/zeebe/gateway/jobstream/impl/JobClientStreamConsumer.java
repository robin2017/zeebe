/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway.jobstream.impl;

import io.camunda.zeebe.gateway.ResponseMapper;
import io.camunda.zeebe.gateway.grpc.ServerStreamObserver;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass;
import io.camunda.zeebe.protocol.impl.record.ActivatedJobImpl;
import io.camunda.zeebe.transport.stream.api.ClientStreamConsumer;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JobClientStreamConsumer implements ClientStreamConsumer {
  private static final Logger LOGGER = LoggerFactory.getLogger(JobClientStreamConsumer.class);
  private final ServerStreamObserver<GatewayOuterClass.ActivatedJob> observer;

  public JobClientStreamConsumer(
      final ServerStreamObserver<GatewayOuterClass.ActivatedJob> observer) {
    this.observer = observer;
  }

  @Override
  public void push(final DirectBuffer payload) {
    final var deserialized = new ActivatedJobImpl();
    deserialized.wrap(payload, 0, payload.capacity());
    LOGGER.trace("Received pushed job {}", deserialized.jobKey());

    final GatewayOuterClass.ActivatedJob activatedJob =
        ResponseMapper.toActivatedJobResponse(deserialized.jobKey(), deserialized.record());

    try {
      observer.onNext(activatedJob);
      LOGGER.trace("Pushed activated job {} to stream observer", activatedJob.getKey());
    } catch (final Exception e) {
      LOGGER.error("Failed to push activated job {} to stream observer", activatedJob.getKey());
      observer.onError(e);
      // TODO: remove observer?
      // TODO: rethrow error so the job is yielded back
    }
  }
}
