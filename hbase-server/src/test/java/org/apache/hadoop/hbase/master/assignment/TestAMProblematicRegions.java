/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.assignment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Category({ MasterTests.class, MediumTests.class })
public class TestAMProblematicRegions extends TestAssignmentManagerBase {
  private static final Logger LOG = LoggerFactory.getLogger(TestAMProblematicRegions.class);

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
      HBaseClassTestRule.forClass(TestAMProblematicRegions.class);

  @Test
  public void testForMeta() throws Exception {
    byte[] metaRegionNameAsBytes = RegionInfoBuilder.FIRST_META_REGIONINFO.getRegionName();
    String metaRegionName = RegionInfoBuilder.FIRST_META_REGIONINFO.getRegionNameAsString();
    List<ServerName> serverNames = master.getServerManager().getOnlineServersList();
    assertEquals(NSERVERS, serverNames.size());

    Map<String, Pair<ServerName, Set<ServerName>>> problematicRegions = am.getProblematicRegions();

    // Test for case1: Master thought this region opened, but no regionserver reported it.
    assertTrue(problematicRegions.containsKey(metaRegionName));
    Pair<ServerName, Set<ServerName>> pair = problematicRegions.get(metaRegionName);
    ServerName locationInMeta = pair.getFirst();
    Set<ServerName> reportedRegionServers = pair.getSecond();
    assertTrue(serverNames.contains(locationInMeta));
    assertEquals(0, reportedRegionServers.size());

    // Reported right region location. Then not in problematic regions.
    am.reportOnlineRegions(locationInMeta, Collections.singleton(metaRegionNameAsBytes));
    problematicRegions = am.getProblematicRegions();
    assertFalse(problematicRegions.containsKey(metaRegionName));
  }

  @Test
  public void testForUserTable() throws Exception {
    TableName tableName = TableName.valueOf("testForUserTable");
    RegionInfo hri = createRegionInfo(tableName, 1);
    String regionName = hri.getRegionNameAsString();
    rsDispatcher.setMockRsExecutor(new GoodRsExecutor());
    Future<byte[]> future = submitProcedure(am.createAssignProcedure(hri));
    waitOnFuture(future);

    List<ServerName> serverNames = master.getServerManager().getOnlineServersList();
    assertEquals(NSERVERS, serverNames.size());

    // Test for case1: Master thought this region opened, but no regionserver reported it.
    Map<String, Pair<ServerName, Set<ServerName>>> problematicRegions = am.getProblematicRegions();
    assertTrue(problematicRegions.containsKey(regionName));
    Pair<ServerName, Set<ServerName>> pair = problematicRegions.get(regionName);
    ServerName locationInMeta = pair.getFirst();
    Set<ServerName> reportedRegionServers = pair.getSecond();
    assertTrue(serverNames.contains(locationInMeta));
    assertEquals(0, reportedRegionServers.size());

    // Test for case2: Master thought this region opened on Server1, but regionserver reported
    // Server2
    final ServerName tempLocationInMeta = locationInMeta;
    final ServerName anotherServer =
        serverNames.stream().filter(s -> !s.equals(tempLocationInMeta)).findFirst().get();
    am.reportOnlineRegions(anotherServer, Collections.singleton(hri.getRegionName()));
    problematicRegions = am.getProblematicRegions();
    assertTrue(problematicRegions.containsKey(regionName));
    pair = problematicRegions.get(regionName);
    locationInMeta = pair.getFirst();
    reportedRegionServers = pair.getSecond();
    assertEquals(1, reportedRegionServers.size());
    assertFalse(reportedRegionServers.contains(locationInMeta));
    assertTrue(reportedRegionServers.contains(anotherServer));

    // Test for case3: More than one regionservers reported opened this region.
    am.reportOnlineRegions(locationInMeta, Collections.singleton(hri.getRegionName()));
    problematicRegions = am.getProblematicRegions();
    assertTrue(problematicRegions.containsKey(regionName));
    pair = problematicRegions.get(regionName);
    locationInMeta = pair.getFirst();
    reportedRegionServers = pair.getSecond();
    assertEquals(2, reportedRegionServers.size());
    assertTrue(reportedRegionServers.contains(locationInMeta));
    assertTrue(reportedRegionServers.contains(anotherServer));

    // Reported right region location. Then not in problematic regions.
    am.reportOnlineRegions(anotherServer, Collections.EMPTY_SET);
    problematicRegions = am.getProblematicRegions();
    assertFalse(problematicRegions.containsKey(regionName));
  }
}
