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
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.status.StatusLogger;

import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.internal.admin.Alert;
import org.apache.geode.internal.admin.remote.AlertListenerMessage;
import org.apache.geode.internal.lang.ThreadUtils;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.tcp.ReenteredConnectException;

/**
 * A Log4j Appender which will notify listeners whenever a message of the requested level is written
 * to the log file.
 */
public final class AlertAppender extends AbstractAppender implements PropertyChangeListener {
  private static final StatusLogger logger = StatusLogger.getLogger();

  private static final String APPENDER_NAME = AlertAppender.class.getName();
  private static final AlertAppender instance = createAlertAppender();

  /** Is this thread in the process of alerting? */
  private static final ThreadLocal<Boolean> alerting = new ThreadLocal<Boolean>() {
    @Override
    protected Boolean initialValue() {
      return Boolean.FALSE;
    }
  };

  // Listeners are ordered with the narrowest levels (e.g. FATAL) at the end
  private final CopyOnWriteArrayList<AlertSubscriber> listeners =
      new CopyOnWriteArrayList<AlertSubscriber>();

  private final AppenderContext appenderContext = LogService.getAppenderContext();

  // This can be set by a loner distributed system to disable alerting
  private volatile boolean alertingDisabled = false;

  private static AlertAppender createAlertAppender() {
    AlertAppender alertAppender = new AlertAppender();
    alertAppender.start();
    return alertAppender;
  }

  public static AlertAppender getInstance() {
    return instance;
  }

  private AlertAppender() {
    super(APPENDER_NAME, null, PatternLayout.createDefaultLayout());
  }

  /**
   * Returns true if the current thread is in the process of delivering an alert message.
   */
  public static boolean isThreadAlerting() {
    return alerting.get();
  }

  public static void setIsAlerting(boolean isAlerting) {
    alerting.set(isAlerting ? Boolean.TRUE : Boolean.FALSE);
  }

  public boolean isAlertingDisabled() {
    return alertingDisabled;
  }

  void setAlertingDisabled(final boolean alertingDisabled) {
    this.alertingDisabled = alertingDisabled;
  }

  /**
   * This method is optimized with the assumption that at least one listener has set a level which
   * requires that the event be sent. This is ensured by modifying the appender's configuration
   * whenever a listener is added or removed.
   */
  @Override
  public void append(final LogEvent event) {
    if (this.alertingDisabled) {
      return;
    }

    // If already appending then don't send to avoid infinite recursion
    if ((alerting.get())) {
      return;
    }
    setIsAlerting(true);

    try {

      final boolean isDebugEnabled = logger.isDebugEnabled();
      if (isDebugEnabled) {
        logger.debug("Delivering an alert event: {}", event);
      }

      InternalDistributedSystem ds = InternalDistributedSystem.getConnectedInstance();
      if (ds == null) {
        // Use info level to avoid triggering another alert
        logger.info("Did not append alert event because the distributed system is set to null.");
        return;
      }
      DistributionManager distMgr = (DistributionManager) ds.getDistributionManager();

      final int intLevel = AlertLevel.logLevelToAlertLevel(event.getLevel().intLevel());
      final Date date = new Date(event.getTimeMillis());
      final String threadName = event.getThreadName();
      final String logMessage = event.getMessage().getFormattedMessage();
      final String stackTrace = ThreadUtils.stackTraceToString(event.getThrown(), true);
      final String connectionName = ds.getConfig().getName();

      for (AlertSubscriber listener : this.listeners) {
        if (event.getLevel().intLevel() > listener.getLevel().intLevel()) {
          break;
        }

        try {
          AlertListenerMessage alertMessage =
              AlertListenerMessage.create(listener.getMember(), intLevel, date, connectionName,
                  threadName, Thread.currentThread().getId(), logMessage, stackTrace);

          if (listener.getMember().equals(distMgr.getDistributionManagerId())) {
            if (isDebugEnabled) {
              logger.debug("Delivering local alert message: {}, {}, {}, {}, {}, [{}], [{}].",
                  listener.getMember(), intLevel, date, connectionName, threadName, logMessage,
                  stackTrace);
            }
            alertMessage.process(distMgr);
          } else {
            if (isDebugEnabled) {
              logger.debug("Delivering remote alert message: {}, {}, {}, {}, {}, [{}], [{}].",
                  listener.getMember(), intLevel, date, connectionName, threadName, logMessage,
                  stackTrace);
            }
            distMgr.putOutgoing(alertMessage);
          }
        } catch (ReenteredConnectException e) {
          // OK. We can't send to this recipient because we're in the middle of
          // trying to connect to it.
        }
      }
    } finally {
      setIsAlerting(false);
    }
  }

  @Override
  public synchronized void propertyChange(final PropertyChangeEvent evt) {
    if (logger.isDebugEnabled()) {
      logger.debug("Responding to a property change event. Property name is {}.",
          evt.getPropertyName());
    }
    if (evt.getPropertyName().equals(LoggerContext.PROPERTY_CONFIG)) {
      LoggerConfig loggerConfig = this.appenderContext.getLoggerConfig();
      if (!loggerConfig.getAppenders().containsKey(APPENDER_NAME)) {
        loggerConfig.addAppender(this, this.listeners.get(0).getLevel(), null);
      }
    }
  }

  synchronized void addAlertSubscriber(final DistributedMember member, final int alertLevel) {
    final Level level = LogService.toLevel(AlertLevel.alertLevelToLogLevel(alertLevel));

    if (this.listeners.size() == 0) {
      this.appenderContext.getLoggerContext().addPropertyChangeListener(this);
    }

    addListenerToSortedList(new AlertSubscriber(level, member));

    LoggerConfig loggerConfig = this.appenderContext.getLoggerConfig();
    loggerConfig.addAppender(this, this.listeners.get(0).getLevel(), null);
    if (logger.isDebugEnabled()) {
      logger.debug("Added/Replaced alert listener for member {} at level {}", member, level);
    }
  }

  synchronized boolean removeAlertSubscriber(final DistributedMember member) {
    final boolean memberWasFound = this.listeners.remove(new AlertSubscriber(null, member));

    if (memberWasFound) {
      if (this.listeners.size() == 0) {
        this.appenderContext.getLoggerContext().removePropertyChangeListener(this);
        this.appenderContext.getLoggerConfig().removeAppender(APPENDER_NAME);

      } else {
        LoggerConfig loggerConfig = this.appenderContext.getLoggerConfig();
        loggerConfig.addAppender(this, this.listeners.get(0).getLevel(), null);
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Removed alert listener for member {}", member);
      }
    }

    return memberWasFound;
  }

  synchronized boolean hasAlertSubscriber(final DistributedMember member, final int alertLevel) {
    final Level level = LogService.toLevel(AlertLevel.alertLevelToLogLevel(alertLevel));

    for (AlertSubscriber listener : this.listeners) {
      if (listener.getMember().equals(member) && listener.getLevel().equals(level)) {
        return true;
      }
    }

    // Special case for alert level Alert.OFF (NONE_LEVEL), because we can never have an actual
    // listener with
    // this level (see AlertLevelChangeMessage.process()).
    if (alertLevel == Alert.OFF) {
      for (AlertSubscriber listener : this.listeners) {
        if (listener.getMember().equals(member)) {
          return false;
        }
      }
      return true;
    }

    return false;
  }

  synchronized void shuttingDown() {
    this.listeners.clear();
    this.appenderContext.getLoggerContext().removePropertyChangeListener(this);
    this.appenderContext.getLoggerConfig().removeAppender(APPENDER_NAME);
  }

  /**
   * Will add (or replace) a listener to the list of sorted listeners such that listeners with a
   * narrower level (e.g. FATAL) will be at the end of the list.
   *
   * @param listener The listener to add to the list.
   */
  private void addListenerToSortedList(final AlertSubscriber listener) {
    if (this.listeners.contains(listener)) {
      this.listeners.remove(listener);
    }

    for (int i = 0; i < this.listeners.size(); i++) {
      if (listener.getLevel().compareTo(this.listeners.get(i).getLevel()) >= 0) {
        this.listeners.add(i, listener);
        return;
      }
    }

    this.listeners.add(listener);
  }

  /**
   * Simple value object which holds an InteralDistributedMember and Level pair.
   */
  static class AlertSubscriber {

    private final Level level;
    private final DistributedMember member;

    AlertSubscriber(final Level level, final DistributedMember member) {
      this.level = level;
      this.member = member;
    }

    public Level getLevel() {
      return this.level;
    }

    public DistributedMember getMember() {
      return this.member;
    }

    /**
     * Never used, but maintain the hashCode/equals contract.
     */
    @Override
    public int hashCode() {
      return 31 + ((this.member == null) ? 0 : this.member.hashCode());
    }

    /**
     * Ignore the level when determining equality.
     */
    @Override
    public boolean equals(Object other) {
      return (this.member.equals(((AlertSubscriber) other).member)) ? true : false;
    }

    @Override
    public String toString() {
      return "Listener [level=" + this.level + ", member=" + this.member + "]";
    }
  }
}
