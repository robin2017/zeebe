/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.robin;

import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.Record;

public class DemoExporter implements Exporter {

  private Controller controller;

  public void configure(Context context) throws Exception {
    System.out.println("----------come into DemoExporter configure------------:" + context);
  }

  public void open(Controller controller) {
    this.controller = controller;
    System.out.println("----------come into DemoExporter open------------:" + controller);
  }

  public void close() {
    System.out.println("----------come into DemoExporter close------------");
  }

  @Override
  public void export(final Record<?> record) {
    System.out.println(
        "----------come into DemoExporter record------------:"
            + record.getPosition()
            + "/"
            + record);
    controller.updateLastExportedRecordPosition(
        record.getPosition(), "demo-exporter-position".getBytes());
  }
}
