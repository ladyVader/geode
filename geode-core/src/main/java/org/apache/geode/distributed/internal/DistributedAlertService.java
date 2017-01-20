/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geode.distributed.internal;

import static org.apache.geode.internal.admin.remote.AlertListenerMessage.create;
import static org.apache.geode.management.internal.alerting.AlertLevel.*;

import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.internal.admin.Alert;
import org.apache.geode.internal.admin.remote.AlertListenerMessage;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.tcp.ReenteredConnectException;
import org.apache.geode.management.internal.alerting.AlertServiceListener;
import org.apache.geode.management.internal.alerting.AlertSubscriber;
import org.apache.geode.management.internal.alerting.AlertService;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.status.StatusLogger;

import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;

public class DistributedAlertService implements AlertService {
  private static final StatusLogger logger = StatusLogger.getLogger();

  /** Is this thread in the process of alerting? */
  private static final ThreadLocal<Boolean> alerting = new ThreadLocal<Boolean>() {
    @Override
    protected Boolean initialValue() {
      return Boolean.FALSE;
    }
  };

  // Listeners are ordered with the narrowest levels (e.g. FATAL) at the end
  private final CopyOnWriteArrayList<AlertSubscriber> alertSubscribers = new CopyOnWriteArrayList<>();

  private final InternalDistributedSystem system;
  private final DistributionManager dm;

  private volatile boolean enabled;

  public DistributedAlertService(final InternalDistributedSystem system) {
    this.system = system;
    this.system.addDisconnectListener((final InternalDistributedSystem disconnected) -> {
      enabled = false;
    });
    this.dm = (DistributionManager)this.system.getDistributionManager();
    this.enabled = !this.system.getDistributionManager().isLoner(); // loner is disabled
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

  public synchronized void shuttingDown() {
    this.alertSubscribers.clear();
  }

  private AlertServiceListener alertServiceListener;

  public synchronized void addAlertListener(final DistributedMember member, final int alertLevel) {
    final Level level = LogService.toLevel(alertLevelToLogLevel(alertLevel));

    if (this.alertSubscribers.size() == 0) {
      alertServiceListener.clear();
    }

    addListenerToSortedList(new AlertSubscriber(level, member));

    LoggerConfig loggerConfig = this.appenderContext.getLoggerConfig();
    loggerConfig.addAppender(this, this.alertSubscribers.get(0).getLevel(), null);
    if (logger.isDebugEnabled()) {
      logger.debug("Added/Replaced alert listener for member {} at level {}", member, level);
    }
  }

  public synchronized boolean removeAlertListener(final DistributedMember member) {
    final boolean memberWasFound = this.listeners.remove(new AlertSubscriber(null, member));

    if (memberWasFound) {
      if (this.alertSubscribers.size() == 0) {
        this.appenderContext.getLoggerContext().removePropertyChangeListener(this);
        this.appenderContext.getLoggerConfig().removeAppender(APPENDER_NAME);

      } else {
        LoggerConfig loggerConfig = this.appenderContext.getLoggerConfig();
        loggerConfig.addAppender(this, this.alertSubscribers.get(0).getLevel(), null);
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Removed alert listener for member {}", member);
      }
    }

    return memberWasFound;
  }

  public synchronized boolean hasAlertListener(final DistributedMember member,
                                               final int alertLevel) {
    final Level level = LogService.toLevel(alertLevelToLogLevel(alertLevel));

    for (AlertSubscriber listener : this.alertSubscribers) {
      if (listener.getMember().equals(member) && listener.getLevel().equals(level)) {
        return true;
      }
    }

    // Special case for alert level Alert.OFF (NONE_LEVEL), because we can never have an actual
    // listener with
    // this level (see AlertLevelChangeMessage.process()).
    if (alertLevel == Alert.OFF) {
      for (AlertSubscriber listener : this.alertSubscribers) {
        if (listener.getMember().equals(member)) {
          return false;
        }
      }
      return true;
    }

    return false;
  }

  /**
   * Will add (or replace) a listener to the list of sorted listeners such that listeners with a
   * narrower level (e.g. FATAL) will be at the end of the list.
   *
   * @param listener The listener to add to the list.
   */
  private void addListenerToSortedList(final AlertSubscriber listener) {
    if (this.alertSubscribers.contains(listener)) {
      this.alertSubscribers.remove(listener);
    }

    for (int i = 0; i < this.alertSubscribers.size(); i++) {
      if (listener.getLevel().compareTo(this.alertSubscribers.get(i).getLevel()) >= 0) {
        this.alertSubscribers.add(i, listener);
        return;
      }
    }

    this.alertSubscribers.add(listener);
  }

  public boolean isEnabled() {
    return this.enabled;
  }

  public DistributionManager getDistributionManager() {
    return this.dm;
  }

  public String getMemberName() {
    return system.getName();
  }

  @Override
  public boolean isAlerting() {
    return false;
  }

  @Override
  public void handleAlert(final AlertService alertProvider,
                          final int intLevel,
                          final Date date,
                          final String threadName,
                          final String logMessage,
                          final String stackTrace,
                          final String connectionName,
                          final AlertSubscriber listener) {

    DistributionManager dm = alertProvider.getDistributionManager();
    if (dm == null) {
      logger.debug("Did not append alert event because the distributed system is set to null.");
      return;
    }

    try {
      setIsAlerting(true);

      AlertListenerMessage alertMessage = create(
          listener.getMember(),
          intLevel,
          date,
          connectionName,
          threadName,
          Thread.currentThread().getId(),
          logMessage,
          stackTrace);

      if (listener.getMember().equals(dm.getDistributionManagerId())) {
        logger.debug("Processing local alert message: {}, {}, {}, {}, {}, [{}], [{}].",
            listener.getMember(),
            intLevel,
            date,
            connectionName,
            threadName,
            logMessage,
            stackTrace);
        alertMessage.process(dm);
      } else {
        logger.debug("Sending remote alert message: {}, {}, {}, {}, {}, [{}], [{}].",
            listener.getMember(),
            intLevel,
            date,
            connectionName,
            threadName,
            logMessage,
            stackTrace);
        dm.putOutgoing(alertMessage);
      }
    } catch (ReenteredConnectException e) {
      // OK. We can't send to this recipient because we're in the middle of
      // trying to connect to it.
    } finally {
      setIsAlerting(false);
    }
  }
}
