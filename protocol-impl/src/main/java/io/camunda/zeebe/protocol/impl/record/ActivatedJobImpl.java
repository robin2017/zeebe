/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.protocol.impl.record;

import io.camunda.zeebe.protocol.impl.record.value.job.JobRecord;
import io.camunda.zeebe.util.buffer.BufferReader;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

public final class ActivatedJobImpl implements ActivatedJob, BufferReader {
  @Override
  public long jobKey() {
    return 0;
  }

  @Override
  public JobRecord record() {
    return null;
  }

  @Override
  public int getLength() {
    return 0;
  }

  @Override
  public void write(final MutableDirectBuffer buffer, final int offset) {}

  @Override
  public void wrap(final DirectBuffer buffer, final int offset, final int length) {}
}
