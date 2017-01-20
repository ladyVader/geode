/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.logging.log4j;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.geode.distributed.internal.DistributedAlertService;
import org.apache.geode.management.internal.alerting.AlertLevel;
import org.apache.geode.management.internal.alerting.AlertServiceListener;
import org.apache.geode.management.internal.alerting.AlertSubscriber;
import org.apache.geode.management.internal.alerting.DefaultAlertHandler;
import org.apache.geode.management.internal.alerting.AlertService;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.internal.lang.ThreadUtils;
import org.apache.geode.internal.logging.LogService;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * A Log4j Appender which will notify listeners whenever a message of the requested level is written
 * to the log file.
 */
public final class AlertAppender extends AbstractAppender implements PropertyChangeListener,
    AlertServiceListener {
  private static final StatusLogger logger = StatusLogger.getLogger();

  private static final String APPENDER_NAME = AlertAppender.class.getName();
  private static final AlertAppender instance = createAlertAppender();
  private static final NullAlertProvider nullAlertProvider = new NullAlertProvider();

  private final AtomicReference<AlertService> alertServiceRef = new AtomicReference<>(nullAlertProvider);

  private final AppenderContext appenderContext;

  private static AlertAppender createAlertAppender() {
    AlertAppender alertAppender = new AlertAppender(LogService.getAppenderContext());
    alertAppender.start();

    return alertAppender;
  }

  private AlertAppender(final AppenderContext appenderContext) {
    super(APPENDER_NAME, null, PatternLayout.createDefaultLayout());
    this.appenderContext = appenderContext;
  }

  public static AlertAppender getInstance() {
    return instance;
  }

  public static void updateAlertService(final AlertService service) {
    instance.alertServiceRef.set(service);
  }

  /**
   * This method is optimized with the assumption that at least one listener has set a level which
   * requires that the event be sent. This is ensured by modifying the appender's configuration
   * whenever a listener is added or removed.
   */
  @Override
  public void append(final LogEvent event) {
    AlertService alertService = this.alertServiceRef.get();
    if (!alertService.isEnabled()) {
      return;
    }

    // If already appending then don't send to avoid infinite recursion
    if (alertService.isAlerting()) {
      return;
    }
    DistributedAlertService.setIsAlerting(true);

    try {
      logger.debug("Delivering an alert event: {}", event);

      int intLevel = AlertLevel.logLevelToAlertLevel(event.getLevel().intLevel());
      Date date = new Date(event.getTimeMillis());
      String threadName = event.getThreadName();
      String logMessage = event.getMessage().getFormattedMessage();
      String stackTrace = ThreadUtils.stackTraceToString(event.getThrown(), true);
      String connectionName = alertService.getMemberName();

      for (AlertSubscriber listener : this.listeners) {
        if (event.getLevel().intLevel() > listener.getLevel().intLevel()) {
          break;
        }

        alertService
            .handleAlert(alertService, intLevel, date, threadName, logMessage, stackTrace,
            connectionName,
            listener);
      }
    } finally {
      DistributedAlertService.setIsAlerting(false);
    }
  }

  @Override
  public synchronized void propertyChange(final PropertyChangeEvent event) {
    if (logger.isDebugEnabled()) {
      logger.debug("Responding to a property change event. Property name is {}.",
          event.getPropertyName());
    }
    if (event.getPropertyName().equals(LoggerContext.PROPERTY_CONFIG)) {
      LoggerConfig loggerConfig = this.appenderContext.getLoggerConfig();
      if (!loggerConfig.getAppenders().containsKey(APPENDER_NAME)) {
        loggerConfig.addAppender(this, this.listeners.get(0).getLevel(), null);
      }
    }
  }

  public synchronized void shuttingDown() {
    this.alertServiceRef.get().shuttingDown();
    this.appenderContext.getLoggerContext().removePropertyChangeListener(this);
    this.appenderContext.getLoggerConfig().removeAppender(APPENDER_NAME);
  }

  @Override
  public void clear() {
    this.appenderContext.getLoggerContext().addPropertyChangeListener(this);
  }

  @Override
  public void lowestLevel(final int level) {

  }

  private static class NullAlertProvider implements AlertService {

    @Override
    public boolean isEnabled() {
      return false;
    }

    @Override
    public DistributionManager getDistributionManager() {
      return null;
    }

    @Override
    public String getMemberName() {
      return null;
    }

    @Override
    public boolean isAlerting() {
      return false;
    }

    @Override
    public void shuttingDown() {

    }

    @Override
    public void handleAlert(final AlertService alertProvider, final int intLevel, final Date date,
                            final String threadName,
                            final String logMessage, final String stackTrace,
                            final String connectionName,
                            final AlertSubscriber listener) {

    }
  }
}
