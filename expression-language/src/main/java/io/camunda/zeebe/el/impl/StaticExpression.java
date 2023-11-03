/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.el.impl;

import static io.camunda.zeebe.el.impl.Loggers.LOGGER;

import io.camunda.zeebe.el.EvaluationResult;
import io.camunda.zeebe.el.EvaluationWarning;
import io.camunda.zeebe.el.Expression;
import io.camunda.zeebe.el.ResultType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.agrona.DirectBuffer;

/**
 * This class handles static expressions of type {@code String} or {@code Number}. Boolean types are
 * not yet implemented. Also the method {@code toBuffer()} is not implemented
 */
public final class StaticExpression implements Expression, EvaluationResult {

  private final String expression;
  private ResultType resultType;
  private Object result;

  public StaticExpression(final String expression) {
    this.expression = expression;

    try {
      treatAsNumber(expression);
    } catch (final NumberFormatException e) {
      treatAsString(expression);
    }
  }

  private void treatAsNumber(final String expression) {
    result = new BigDecimal(expression);
    resultType = ResultType.NUMBER;
  }

  private void treatAsString(final String expression) {
    result = expression;
    resultType = ResultType.STRING;
  }

  @Override
  public String getExpression() {
    return expression;
  }

  @Override
  public Optional<String> getVariableName() {
    return Optional.empty();
  }

  @Override
  public boolean isStatic() {
    return true;
  }

  @Override
  public boolean isValid() {
    return true;
  }

  @Override
  public String getFailureMessage() {
    return null;
  }

  @Override
  public boolean isFailure() {
    return false;
  }

  @Override
  public List<EvaluationWarning> getWarnings() {
    return Collections.emptyList();
  }

  @Override
  public ResultType getType() {
    return resultType;
  }

  @Override
  public DirectBuffer toBuffer() {
    LOGGER.warn("StaticExpression.toBuffer() - not yet implemented");
    return null;
  }

  @Override
  public String getString() {
    return getType() == ResultType.STRING ? (String) result : null;
  }

  @Override
  public Boolean getBoolean() {
    LOGGER.warn("StaticExpression.getBoolean() - not yet implemented");
    return null;
  }

  @Override
  public Number getNumber() {
    return getType() == ResultType.NUMBER ? (Number) result : null;
  }

  @Override
  public Duration getDuration() {
    return null;
  }

  @Override
  public Period getPeriod() {
    return null;
  }

  @Override
  public ZonedDateTime getDateTime() {
    return null;
  }

  @Override
  public List<DirectBuffer> getList() {
    return null;
  }

  @Override
  public List<String> getListOfStrings() {
    return null;
  }
}
