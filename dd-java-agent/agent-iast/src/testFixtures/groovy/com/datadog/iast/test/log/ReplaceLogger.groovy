package com.datadog.iast.test.log

import org.junit.rules.ExternalResource
import org.slf4j.Logger

import java.lang.reflect.Field
import java.lang.reflect.Modifier

class ReplaceLogger extends ExternalResource {

  private final Field logField
  private final Logger logger
  private Logger originalLogger

  ReplaceLogger(final Class<?> logClass, final String field, final Logger mockLogger) {
    logField = logClass.getDeclaredField(field)
    logger = mockLogger
  }

  @Override
  protected void before() throws Throwable {
    logField.accessible = true

    Field modifiersField = Field.getDeclaredField('modifiers')
    modifiersField.accessible = true
    modifiersField.setInt(logField, logField.getModifiers() & ~Modifier.FINAL)

    originalLogger = (Logger) logField.get(null)
    logField.set(null, logger)
  }

  @Override
  protected void after() {
    logField.set(null, originalLogger)
  }
}
