/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.incident;

import static io.camunda.zeebe.engine.processing.incident.IncidentHelper.assertIncidentCreated;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.intent.IncidentIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.ErrorType;
import io.camunda.zeebe.protocol.record.value.IncidentRecordValue;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.test.util.Strings;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.util.function.Function;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public final class CallActivityIncidentTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();

  private static final String PROCESS_ID_VARIABLE = "wfChild";

  private static final Function<String, BpmnModelInstance>
      PROCESS_PARENT_PROCESS_ID_EXPRESSION_SUPPLIER =
          (parentProcessId) ->
              Bpmn.createExecutableProcess(parentProcessId)
                  .startEvent()
                  .callActivity("call", c -> c.zeebeProcessIdExpression(PROCESS_ID_VARIABLE))
                  .done();

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  private String parentProcessId;
  private String childProcessId;

  @Before
  public void init() {
    parentProcessId = Strings.newRandomValidBpmnId();
    childProcessId = Strings.newRandomValidBpmnId();
    ENGINE
        .deployment()
        .withXmlResource(
            "wf-parent.bpmn",
            Bpmn.createExecutableProcess(parentProcessId)
                .startEvent()
                .callActivity("call", c -> c.zeebeProcessId(childProcessId))
                .done())
        .deploy();
  }

  @Test
  public void shouldCreateIncidentIfProcessIsNotDeployed() {
    // when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(parentProcessId).create();

    // then
    final Record<IncidentRecordValue> incident = getIncident(processInstanceKey);
    final Record<ProcessInstanceRecordValue> elementInstance =
        getCallActivityInstance(processInstanceKey);

    assertIncidentCreated(incident, elementInstance)
        .hasErrorType(ErrorType.CALLED_ELEMENT_ERROR)
        .hasErrorMessage(
            "Expected process with BPMN process id '"
                + childProcessId
                + "' to be deployed, but not found.");
  }

  @Test
  public void shouldCreateIncidentIfProcessHasNoNoneStartEvent() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(childProcessId)
                .startEvent()
                .message("start")
                .endEvent()
                .done())
        .deploy();

    // when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(parentProcessId).create();

    // then
    final Record<IncidentRecordValue> incident = getIncident(processInstanceKey);
    final Record<ProcessInstanceRecordValue> elementInstance =
        getCallActivityInstance(processInstanceKey);

    assertIncidentCreated(incident, elementInstance)
        .hasErrorType(ErrorType.CALLED_ELEMENT_ERROR)
        .hasErrorMessage(
            "Expected process with BPMN process id '"
                + childProcessId
                + "' to have a none start event, but not found.");
  }

  @Test
  public void shouldCreateIncidentIfProcessIdVariableNotExists() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(PROCESS_PARENT_PROCESS_ID_EXPRESSION_SUPPLIER.apply(parentProcessId))
        .deploy();

    // when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(parentProcessId).create();

    // then
    final Record<IncidentRecordValue> incident = getIncident(processInstanceKey);
    final Record<ProcessInstanceRecordValue> elementInstance =
        getCallActivityInstance(processInstanceKey);

    assertIncidentCreated(incident, elementInstance)
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            """
            Expected result of the expression 'wfChild' to be 'STRING', but was 'NULL'. \
            The evaluation reported the following warnings: \
            [NO_VARIABLE_FOUND] No variable found with name 'wfChild'""");
  }

  @Test
  public void shouldCreateIncidentIfProcessIdVariableIsNaString() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(PROCESS_PARENT_PROCESS_ID_EXPRESSION_SUPPLIER.apply(parentProcessId))
        .deploy();

    // when
    final long processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(parentProcessId)
            .withVariable(PROCESS_ID_VARIABLE, 123)
            .create();

    // then
    final Record<IncidentRecordValue> incident = getIncident(processInstanceKey);
    final Record<ProcessInstanceRecordValue> elementInstance =
        getCallActivityInstance(processInstanceKey);

    assertIncidentCreated(incident, elementInstance)
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "Expected result of the expression '"
                + PROCESS_ID_VARIABLE
                + "' to be 'STRING', but was 'NUMBER'.");
  }

  @Test
  public void shouldCreateIncidentOnCallActivityForCustomTenant() {
    // given
    final String tenantId = "acme";
    ENGINE
        .deployment()
        .withXmlResource(PROCESS_PARENT_PROCESS_ID_EXPRESSION_SUPPLIER.apply(parentProcessId))
        .withTenantId(tenantId)
        .deploy();

    // when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(parentProcessId).withTenantId(tenantId).create();

    // then
    final Record<IncidentRecordValue> incident = getIncident(processInstanceKey);
    final Record<ProcessInstanceRecordValue> elementInstance =
        getCallActivityInstance(processInstanceKey);

    assertIncidentCreated(incident, elementInstance, tenantId)
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            """
            Expected result of the expression 'wfChild' to be 'STRING', but was 'NULL'. \
            The evaluation reported the following warnings: \
            [NO_VARIABLE_FOUND] No variable found with name 'wfChild'""");
  }

  @Test
  public void shouldResolveIncident() {
    // given
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(parentProcessId).create();

    final Record<IncidentRecordValue> incident = getIncident(processInstanceKey);

    // when
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(childProcessId).startEvent().endEvent().done())
        .deploy();

    ENGINE.incident().ofInstance(processInstanceKey).withKey(incident.getKey()).resolve();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .onlyEvents()
                .withRecordKey(incident.getValue().getElementInstanceKey())
                .limit(2))
        .extracting(Record::getIntent)
        .contains(ProcessInstanceIntent.ELEMENT_ACTIVATED);
  }

  @Test
  public void shouldResolveIncidentWithMessageBoundaryEvent() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(parentProcessId)
                .startEvent()
                .callActivity("call", c -> c.zeebeProcessId(childProcessId))
                .boundaryEvent("boundary")
                .message(m -> m.name("message").zeebeCorrelationKeyExpression("123"))
                .endEvent()
                .done())
        .deploy();

    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(parentProcessId).create();

    final Record<IncidentRecordValue> incident = getIncident(processInstanceKey);

    // when
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(childProcessId).startEvent().endEvent().done())
        .deploy();

    ENGINE.incident().ofInstance(processInstanceKey).withKey(incident.getKey()).resolve();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .onlyEvents()
                .withRecordKey(incident.getValue().getElementInstanceKey())
                .limit(2))
        .extracting(Record::getIntent)
        .contains(ProcessInstanceIntent.ELEMENT_ACTIVATED);
  }

  private Record<ProcessInstanceRecordValue> getCallActivityInstance(
      final long processInstanceKey) {
    return RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATING)
        .withProcessInstanceKey(processInstanceKey)
        .withElementType(BpmnElementType.CALL_ACTIVITY)
        .getFirst();
  }

  private Record<IncidentRecordValue> getIncident(final long processInstanceKey) {
    return RecordingExporter.incidentRecords(IncidentIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();
  }
}
