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
package org.apache.geode.management.internal.cli.commands;

import static org.apache.geode.distributed.ConfigurationProperties.GROUPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertNotNull;

import org.apache.geode.internal.ClassBuilder;
import org.apache.geode.internal.ClassPathLoader;
import org.apache.geode.management.internal.cli.i18n.CliStrings;
import org.apache.geode.management.internal.cli.result.CommandResult;
import org.apache.geode.test.dunit.rules.GfshShellConnectionRule;
import org.apache.geode.test.dunit.rules.Locator;
import org.apache.geode.test.dunit.rules.LocatorServerStartupRule;
import org.apache.geode.test.dunit.rules.Server;
import org.apache.geode.test.junit.categories.DistributedTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.File;
import java.io.Serializable;
import java.util.Properties;

/**
 * Unit tests for the DeployCommands class
 * @since GemFire 7.0
 */
@SuppressWarnings("serial")
@Category(DistributedTest.class)
public class DeployCommandsDUnitTest implements Serializable{
  private static final String GROUP1 = "Group1";
  private static final String GROUP2 = "Group2";
  private File newDeployableJarFile;
  private ClassBuilder classBuilder;

  @Rule
  public LocatorServerStartupRule lsRule = new LocatorServerStartupRule();

  @Rule
  public transient GfshShellConnectionRule gfshConnector = new GfshShellConnectionRule();

  private Locator locator;
  private Server server1;
  private Server server2;

  @Before
  public void setup() throws Exception {
    this.newDeployableJarFile = new File(lsRule.getTempFolder().getRoot().getCanonicalPath()
        + File.separator + "DeployCommandsDUnit1.jar");
    this.classBuilder = new ClassBuilder();

    locator = lsRule.startLocatorVM(0);
    gfshConnector.connectAndVerify(locator);

    Properties props = new Properties();
    props.setProperty(GROUPS, GROUP1);
    server1 = lsRule.startServerVM(1, props, locator.getPort());

    props.setProperty(GROUPS, GROUP2);
    server2 = lsRule.startServerVM(2, props, locator.getPort());
  }

  @Test
  public void testDeploy() throws Exception {
    String class1 = "DeployCommandsDUnitA";
    String class2 = "DeployCommandsDUnitB";
    String class3 = "DeployCommandsDUnitC";
    String class4 = "DeployCommandsDUnitD";

    String jarName1 = "DeployCommandsDUnit1.jar";
    String jarName2 = "DeployCommandsDUnit2.jar";
    String jarName3 = "DeployCommandsDUnit3.jar";
    String jarName4 = "DeployCommandsDUnit4.jar";

    File dirWithJarsToDeploy = lsRule.getTempFolder().newFolder();
    File jar1 = new File(dirWithJarsToDeploy, jarName1);
    File jar2 = new File(dirWithJarsToDeploy, jarName2);

    File subDirWithJarsToDeploy = new File(dirWithJarsToDeploy, "subdir");
    subDirWithJarsToDeploy.mkdirs();
    File jar3 = new File(subDirWithJarsToDeploy, jarName3);
    File jar4 = new File(subDirWithJarsToDeploy, jarName4);

    classBuilder.writeJarFromName(class1, jar1);
    classBuilder.writeJarFromName(class2, jar2);
    classBuilder.writeJarFromName(class3, jar3);
    classBuilder.writeJarFromName(class4, jar4);

    // Deploy the JAR
    CommandResult cmdResult =
        gfshConnector.executeAndVerifyCommand("deploy --jar=" + jar1);

    String resultString = commandResultToString(cmdResult);
    assertThat(resultString).contains(server1.getName());
    assertThat(resultString).contains(server2.getName());
    assertThat(resultString).contains(jarName1);

    server1.invoke(() -> assertThatCanLoad(jarName1, class1));
    server2.invoke(() -> assertThatCanLoad(jarName1, class1));

    // Single JAR with group
    cmdResult =
        gfshConnector.executeAndVerifyCommand("deploy --jar=" + jar2 + " --group=" + GROUP1);
    resultString = commandResultToString(cmdResult);

    assertThat(resultString).contains(server1.getName());
    assertThat(resultString).doesNotContain(server2.getName());
    assertThat(resultString).contains(jarName2);

    server1.invoke(() -> assertThatCanLoad(jarName2, class2));
    server2.invoke(() -> assertThatCannotLoad(jarName2, class2));

    //Deploy of multiple JARs to a group by specifying dir
    cmdResult =
        gfshConnector.executeAndVerifyCommand(
            "deploy --group=" + GROUP1 + " --dir=" + subDirWithJarsToDeploy.getCanonicalPath());
    resultString = commandResultToString(cmdResult);

    assertThat(resultString).describedAs(resultString).contains(server1.getName());
    assertThat(resultString).doesNotContain(server2.getName());
    assertThat(resultString).contains(jarName3);
    assertThat(resultString).contains(jarName4);

    server1.invoke(() -> {
      assertThatCanLoad(jarName3, class3);
      assertThatCanLoad(jarName4, class4);
    });
    server2.invoke(() -> {
      assertThatCannotLoad(jarName3, class3);
      assertThatCannotLoad(jarName4, class4);
    });

  //Test undeploy of multiple jars (3&4) by specifying group
    gfshConnector.executeAndVerifyCommand("undeploy --group=" + GROUP1);
    server1.invoke(() -> {
      assertThatCannotLoad(jarName3, class3);
      assertThatCannotLoad(jarName4, class4);
    });
    server2.invoke(() -> {
      assertThatCannotLoad(jarName3, class3);
      assertThatCannotLoad(jarName4, class4);
    });

//Redeploy and test undeploy of multiple jars (3&4) by specifying jar names
    gfshConnector.executeAndVerifyCommand(
        "deploy --dir=" + subDirWithJarsToDeploy.getCanonicalPath());

    server1.invoke(() -> {
      assertThatCanLoad(jarName3, class3);
      assertThatCanLoad(jarName4, class4);
    });
    server2.invoke(() -> {
      assertThatCanLoad(jarName3, class3);
      assertThatCanLoad(jarName4, class4);
    });

    gfshConnector
        .executeAndVerifyCommand("undeploy --jars=" + jar3.getName() + "," + jar4.getName());
    server1.invoke(() -> {
      assertThatCannotLoad(jarName3, class3);
      assertThatCannotLoad(jarName4, class4);
    });
    server2.invoke(() -> {
      assertThatCannotLoad(jarName3, class3);
      assertThatCannotLoad(jarName4, class4);
    });
  }

  private void assertThatCanLoad(String jarName, String className) throws ClassNotFoundException {
    assertThat(ClassPathLoader.getLatest().getJarDeployer().findDeployedJar(jarName)).isNotNull();
    assertThat(ClassPathLoader.getLatest().forName(className)).isNotNull();
  }

  private void assertThatCannotLoad(String jarName, String className) {
    assertThat(ClassPathLoader.getLatest().getJarDeployer().findDeployedJar(jarName)).isNull();
    assertThatThrownBy(() -> ClassPathLoader.getLatest().forName(className))
        .isExactlyInstanceOf(ClassNotFoundException.class);
  }

  //TODO: Add test that deploys version 1, deploys version 2 over, and invokes function from that jar

  @Test
  public void testListDeployed() throws Exception {
    String class1 = "DeployCommandsDUnitA";
    String class2 = "DeployCommandsDUnitB";

    String jarName1 = "DeployCommandsDUnit1.jar";
    String jarName2 = "DeployCommandsDUnit2.jar";

    File dirWithJarsToDeploy = lsRule.getTempFolder().newFolder();
    File jar1 = new File(dirWithJarsToDeploy, jarName1);
    File jar2 = new File(dirWithJarsToDeploy, jarName2);

    classBuilder.writeJarFromName(class1, jar1);
    classBuilder.writeJarFromName(class2, jar2);

    // Deploy a couple of JAR files which can be listed
    gfshConnector.executeAndVerifyCommand(
        "deploy jar --group=" + GROUP1 + " --jar=" + jar1.getCanonicalPath());
    gfshConnector.executeAndVerifyCommand(
        "deploy jar --group=" + GROUP2 + " --jar=" + jar2.getCanonicalPath());

    // List for all members
    CommandResult commandResult = gfshConnector.executeAndVerifyCommand("list deployed");
    String resultString = commandResultToString(commandResult);
    assertThat(resultString).contains(server1.getName());
    assertThat(resultString).contains(server2.getName());
    assertThat(resultString).contains(jarName1);
    assertThat(resultString).contains(jarName2);

    // List for members in Group1
    commandResult = gfshConnector.executeAndVerifyCommand("list deployed --group=" + GROUP1);
    resultString = commandResultToString(commandResult);
    assertThat(resultString).contains(server1.getName());
    assertThat(resultString).doesNotContain(server2.getName());

    assertThat(resultString).contains(jarName1);
    assertThat(resultString).doesNotContain(jarName2);

    // List for members in Group2
    commandResult = gfshConnector.executeAndVerifyCommand("list deployed --group=" + GROUP2);
    resultString = commandResultToString(commandResult);
    assertThat(resultString).doesNotContain(server1.getName());
    assertThat(resultString).contains(server2.getName());

    assertThat(resultString).doesNotContain(jarName1);
    assertThat(resultString).contains(jarName2);
  }

  /**
   * Does an end-to-end test using the complete CLI framework while ensuring that the shared
   * configuration is updated.
   */
  @Test
  public void testEndToEnd() throws Exception {
    String jarName = "DeployCommandsDUnit1.jar";
    String className = "DeployCommandsDUnitA";

    // Create a JAR file
    this.classBuilder.writeJarFromName(className, this.newDeployableJarFile);

    // Deploy the JAR
    CommandResult cmdResult = gfshConnector.executeAndVerifyCommand(
            "deploy --jar=" + this.newDeployableJarFile.getCanonicalPath());

    String stringResult = commandResultToString(cmdResult);
    assertThat(stringResult).contains("Member").contains("JAR")
        .contains("JAR Location");
    assertThat(stringResult).contains(server1.getName()).contains(jarName)
        .contains("DeployCommandsDUnit1.v1.jar");
    server1.invoke(() -> assertThatCanLoad(jarName, className));

    // Undeploy the JAR
    cmdResult = gfshConnector.executeAndVerifyCommand("undeploy --jar="+jarName);

    stringResult = commandResultToString(cmdResult);
    assertThat(stringResult).contains("Member").contains("JAR")
        .contains("Un-Deployed From JAR Location");
    assertThat(stringResult).contains(server1.getName()).contains(jarName)
        .contains("DeployCommandsDUnit1.v1.jar");

    server1.invoke(() -> assertThatCannotLoad(jarName, className));

    // Deploy the JAR to a group
    cmdResult = gfshConnector.executeAndVerifyCommand(
        "deploy --jar=" + this.newDeployableJarFile.getCanonicalPath() + " --group=" + GROUP1);
    stringResult = commandResultToString(cmdResult);
    assertThat(stringResult).contains("Member").contains("JAR").contains("JAR Location");
    assertThat(stringResult).contains(server1.getName()).contains(jarName)
        .contains("DeployCommandsDUnit1.v2.jar");

    server1.invoke(() -> assertThatCanLoad(jarName, className));

    // List deployed for group
    cmdResult = gfshConnector.executeAndVerifyCommand("list deployed --group=" + GROUP1);
    stringResult = commandResultToString(cmdResult);
    assertThat(stringResult).contains("Member").contains("JAR").contains("JAR Location");
    assertThat(stringResult).contains(server1.getName()).contains(jarName)
        .contains("DeployCommandsDUnit1.v2.jar");

    // Undeploy for group (without specifying jar name)
    cmdResult = gfshConnector.executeAndVerifyCommand("undeploy --group=" + GROUP1);
    stringResult = commandResultToString(cmdResult);
    assertThat(stringResult).contains("Member").contains("JAR")
        .contains("Un-Deployed From JAR Location");
    assertThat(stringResult).contains(server1.getName()).contains(jarName)
        .contains("DeployCommandsDUnit1.v2.jar");
    server1.invoke(() -> assertThatCannotLoad(jarName, className));

    // List deployed with nothing deployed
    cmdResult = gfshConnector.executeAndVerifyCommand("list deployed");
    assertThat(
        commandResultToString(cmdResult)).contains(CliStrings.LIST_DEPLOYED__NO_JARS_FOUND_MESSAGE);
  }

  protected static String commandResultToString(final CommandResult commandResult) {
    assertNotNull(commandResult);

    commandResult.resetToFirstLine();

    StringBuilder buffer = new StringBuilder(commandResult.getHeader());

    while (commandResult.hasNextLine()) {
      buffer.append(commandResult.nextLine());
    }

    buffer.append(commandResult.getFooter());

    return buffer.toString();
  }
}
