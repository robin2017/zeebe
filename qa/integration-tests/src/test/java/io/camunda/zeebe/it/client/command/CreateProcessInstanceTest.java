/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.client.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.camunda.zeebe.broker.test.EmbeddedBrokerRule;
import io.camunda.zeebe.client.api.command.ClientException;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.it.util.GrpcClientRule;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceCreationIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.test.util.BrokerClassRuleHelper;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import java.util.Map;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public final class CreateProcessInstanceTest {

  private static final EmbeddedBrokerRule BROKER_RULE = new EmbeddedBrokerRule();
  private static final GrpcClientRule CLIENT_RULE = new GrpcClientRule(BROKER_RULE);

  @ClassRule
  public static RuleChain ruleChain = RuleChain.outerRule(BROKER_RULE).around(CLIENT_RULE);

  @Rule public final BrokerClassRuleHelper helper = new BrokerClassRuleHelper();

  private String processId;
  private String processId2;
  private long firstProcessDefinitionKey;
  private long secondProcessDefinitionKey;

  @Before
  public void deployProcess() {
    processId = helper.getBpmnProcessId();

    firstProcessDefinitionKey =
        CLIENT_RULE.deployProcess(Bpmn.createExecutableProcess(processId).startEvent("v1").done());
    secondProcessDefinitionKey =
        CLIENT_RULE.deployProcess(
            Bpmn.createExecutableProcess(processId)
                .startEvent("v2")
                .parallelGateway()
                .endEvent("end1")
                .moveToLastGateway()
                .endEvent("end2")
                .done());

    processId2 = "%s-2".formatted(helper.getBpmnProcessId());
    CLIENT_RULE.deployProcess(
        Bpmn.createExecutableProcess(processId2)
            .eventSubProcess(
                "event-sub",
                e ->
                    e.startEvent("msg-start-event")
                        .message(msg -> msg.name("msg").zeebeCorrelationKey("=missing_var")))
            .startEvent("v3")
            .endEvent("end")
            .done());
  }

  @Test
  public void shouldCreateBpmnProcessById() {
    // when
    final ProcessInstanceEvent processInstance =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .latestVersion()
            .send()
            .join();

    // then
    assertThat(processInstance.getBpmnProcessId()).isEqualTo(processId);
    assertThat(processInstance.getVersion()).isEqualTo(2);
    assertThat(processInstance.getProcessDefinitionKey()).isEqualTo(secondProcessDefinitionKey);
  }

  @Test
  public void shouldCreateBpmnProcessByIdAndVersion() {
    // when
    final ProcessInstanceEvent processInstance =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .version(1)
            .send()
            .join();

    // then instance is created of first process version
    assertThat(processInstance.getBpmnProcessId()).isEqualTo(processId);
    assertThat(processInstance.getVersion()).isEqualTo(1);
    assertThat(processInstance.getProcessDefinitionKey()).isEqualTo(firstProcessDefinitionKey);
  }

  @Test
  public void shouldCreateBpmnProcessByKey() {
    // when
    final ProcessInstanceEvent processInstance =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .processDefinitionKey(firstProcessDefinitionKey)
            .send()
            .join();

    // then
    assertThat(processInstance.getBpmnProcessId()).isEqualTo(processId);
    assertThat(processInstance.getVersion()).isEqualTo(1);
    assertThat(processInstance.getProcessDefinitionKey()).isEqualTo(firstProcessDefinitionKey);
  }

  @Test
  public void shouldCreateWithVariables() {
    // given
    final Map<String, Object> variables = Map.of("foo", 123);

    // when
    final ProcessInstanceEvent event =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .latestVersion()
            .variables(variables)
            .send()
            .join();

    // then
    final var createdEvent =
        RecordingExporter.processInstanceCreationRecords()
            .withIntent(ProcessInstanceCreationIntent.CREATED)
            .withInstanceKey(event.getProcessInstanceKey())
            .getFirst();

    assertThat(createdEvent.getValue().getVariables()).containsExactlyEntriesOf(variables);
  }

  @Test
  public void shouldCreateWithoutVariables() {
    // when
    final ProcessInstanceEvent event =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .latestVersion()
            .send()
            .join();

    // then
    final var createdEvent =
        RecordingExporter.processInstanceCreationRecords()
            .withIntent(ProcessInstanceCreationIntent.CREATED)
            .withInstanceKey(event.getProcessInstanceKey())
            .getFirst();

    assertThat(createdEvent.getValue().getVariables()).isEmpty();
  }

  @Test
  public void shouldCreateWithNullVariables() {
    // when
    final ProcessInstanceEvent event =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .latestVersion()
            .variables("null")
            .send()
            .join();

    // then
    final var createdEvent =
        RecordingExporter.processInstanceCreationRecords()
            .withIntent(ProcessInstanceCreationIntent.CREATED)
            .withInstanceKey(event.getProcessInstanceKey())
            .getFirst();

    assertThat(createdEvent.getValue().getVariables()).isEmpty();
  }

  @Test
  public void shouldCreateWithSingleVariable() {
    // given
    final String key = "key";
    final String value = "value";

    // when
    final ProcessInstanceEvent event =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .latestVersion()
            .variable(key, value)
            .send()
            .join();

    // then
    final var createdEvent =
        RecordingExporter.processInstanceCreationRecords()
            .withIntent(ProcessInstanceCreationIntent.CREATED)
            .withInstanceKey(event.getProcessInstanceKey())
            .getFirst();

    assertThat(createdEvent.getValue().getVariables()).containsExactlyEntriesOf(Map.of(key, value));
  }

  @Test
  public void shouldThrowErrorWhenTryToCreateInstanceWithNullVariable() {
    // when
    assertThatThrownBy(
            () ->
                CLIENT_RULE
                    .getClient()
                    .newCreateInstanceCommand()
                    .bpmnProcessId(processId)
                    .latestVersion()
                    .variable(null, null)
                    .send()
                    .join())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void shouldRejectCompleteJobIfVariablesAreInvalid() {
    // when
    final var command =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId)
            .latestVersion()
            .variables("[]")
            .send();

    assertThatThrownBy(command::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining(
            "Property 'variables' is invalid: Expected document to be a root level object, but was 'ARRAY'");
  }

  @Test
  public void shouldRejectCreateBpmnProcessByNonExistingId() {
    // when
    final var command =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId("non-existing")
            .latestVersion()
            .send();

    assertThatThrownBy(command::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining(
            "Expected to find process definition with process ID 'non-existing', but none found");
  }

  @Test
  public void shouldRejectCreateBpmnProcessByNonExistingKey() {
    // when
    final var command =
        CLIENT_RULE.getClient().newCreateInstanceCommand().processDefinitionKey(123L).send();

    assertThatThrownBy(command::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Expected to find process definition with key '123', but none found");
  }

  @Test
  public void shouldCreateWithStartInstructions() {
    // when
    final var instance =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .processDefinitionKey(secondProcessDefinitionKey)
            .startBeforeElement("end1")
            .startBeforeElement("end2")
            .send()
            .join();

    final var processInstanceKey = instance.getProcessInstanceKey();

    // then
    assertThat(processInstanceKey).isPositive();

    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted()
                .withIntent(ProcessInstanceIntent.ELEMENT_ACTIVATED))
        .extracting(Record::getValue)
        .extracting(
            ProcessInstanceRecordValue::getBpmnElementType,
            ProcessInstanceRecordValue::getElementId)
        .describedAs("Expect that both end events are activated")
        .contains(
            tuple(BpmnElementType.END_EVENT, "end1"), tuple(BpmnElementType.END_EVENT, "end2"))
        .describedAs("Expect that the start event is not activated")
        .doesNotContain(tuple(BpmnElementType.START_EVENT, "v2"));
  }

  @Test
  public void shouldRejectCreateWithStartInstructions() {
    // when
    final var command =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .bpmnProcessId(processId2)
            .latestVersion()
            // without variables
            .startBeforeElement("end")
            .send();

    assertThatThrownBy(command::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining(
            """
            Expected to subscribe to catch event(s) of \
            'process-shouldRejectCreateWithStartInstructions-2' but \
            Failed to extract the correlation key for 'missing_var': \
            The value must be either a string or a number, but was 'NULL'. \
            The evaluation reported the following warnings: \
            [NO_VARIABLE_FOUND] No variable found with name 'missing_var'""");
  }
}
