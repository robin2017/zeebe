/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.protocol.impl.record;

import io.camunda.zeebe.util.buffer.BufferWriter;
import java.util.Collection;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

public class JobActivationPropertiesImpl implements JobActivationProperties, BufferWriter {
  @Override
  public DirectBuffer worker() {
    return null;
  }

  @Override
  public Collection<DirectBuffer> fetchVariables() {
    return null;
  }

  @Override
  public long timeout() {
    return 0;
  }

  @Override
  public void wrap(final DirectBuffer buffer, final int offset, final int length) {}

  @Override
  public int getLength() {
    return 0;
  }

  @Override
  public void write(final MutableDirectBuffer buffer, final int offset) {}
}
