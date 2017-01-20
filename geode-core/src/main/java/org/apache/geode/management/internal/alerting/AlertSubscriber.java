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
package org.apache.geode.management.internal.alerting;

import org.apache.geode.distributed.DistributedMember;
import org.apache.logging.log4j.Level;

/**
 * Simple value object which holds a DistributedMember and Level pair.
 */
public class AlertSubscriber {

  private final Level level;
  private final DistributedMember member;

  public AlertSubscriber(final Level level, final DistributedMember member) {
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
    return "AlertSubscriber [level=" + this.level + ", member=" + this.member + "]";
  }
}
