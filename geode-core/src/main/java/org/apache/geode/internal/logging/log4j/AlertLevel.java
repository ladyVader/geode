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

import org.apache.logging.log4j.Level;

import org.apache.geode.internal.admin.Alert;

public class AlertLevel {

  /**
   * Converts an int alert level to an int log level.
   *
   * @param alertLevel The int value for the alert level
   * @return The int value for the matching log level
   * @throws IllegalArgumentException If there is no matching log level
   */
  public static int alertLevelToLogLevel(final int alertLevel) {
    switch (alertLevel) {
      case Alert.SEVERE:
        return Level.FATAL.intLevel();
      case Alert.ERROR:
        return Level.ERROR.intLevel();
      case Alert.WARNING:
        return Level.WARN.intLevel();
      case Alert.OFF:
        return Level.OFF.intLevel();
    }

    throw new IllegalArgumentException("Unknown Alert level [" + alertLevel + "].");
  }

  /**
   * Converts an int log level to an int alert level.
   *
   * @param logLevel The int value for the log level
   * @return The int value for the matching alert level
   * @throws IllegalArgumentException If there is no matching log level
   */
  public static int logLevelToAlertLevel(final int logLevel) {
    if (logLevel == Level.FATAL.intLevel()) {
      return Alert.SEVERE;
    } else if (logLevel == Level.ERROR.intLevel()) {
      return Alert.ERROR;
    } else if (logLevel == Level.WARN.intLevel()) {
      return Alert.WARNING;
    } else if (logLevel == Level.OFF.intLevel()) {
      return Alert.OFF;
    }

    throw new IllegalArgumentException("Unknown Log level [" + logLevel + "].");
  }
}
