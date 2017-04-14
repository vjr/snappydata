/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql

import io.snappydata.cluster.ClusterManagerTestBase


class ColumnBatchScanDUnitTest(s: String) extends ClusterManagerTestBase(s) {

  def testColumnBatchSkipping(): Unit = {

  }

  def _testColumnBatchSkipping(): Unit = {

    val snc = SnappyContext(sc)
    val ddlStr = "YearI INT NOT NULL," +
        "MonthI INT NOT NULL," +
        "DayOfMonth INT NOT NULL," +
        "DepTime INT," +
        "ArrDelay INT," +
        "UniqueCarrier CHAR(6) NOT NULL"

    // reduce the batch size to ensure that multiple are created

    snc.sql(s"create table if not exists airline ($ddlStr) " +
        s" using column options (Buckets '2', COLUMN_BATCH_SIZE '4')")

    import snc.implicits._

    val ds = snc.createDataset(sc.range(1, 101).map(i =>
      AirlineData(2015, 2, 15, 1002, i.toInt, "AA" + i)))
    ds.write.insertInto("airline")

    // ***Check for the case when all the column batches are scanned ****
    var previousExecutionIds = snc.sharedState.listener.executionIdToData.keySet

    val df_allColumnBatchesScan = snc.sql(
      "select AVG(ArrDelay) arrivalDelay, UniqueCarrier carrier " +
          "from AIRLINE where  ArrDelay < 101 " +
          "group by UniqueCarrier order by arrivalDelay")

    df_allColumnBatchesScan.count()

    var executionIds =
      snc.sharedState.listener.executionIdToData.keySet.diff(previousExecutionIds)

    var executionId = executionIds.head

    val (scanned1, skipped1) =
      findColumnBatchStats(df_allColumnBatchesScan, snc.snappySession, executionId)
    assert(skipped1 == 0, "All Column batches should have been scanned")
    assert(scanned1 > 0, "All Column batches should have been scanned")

    // ***Check for the case when all the column batches are skipped****
    previousExecutionIds = snc.sharedState.listener.executionIdToData.keySet

    val df_noColumnBatchesScan = snc.sql(
      "select AVG(ArrDelay) arrivalDelay, UniqueCarrier carrier " +
          "from AIRLINE where ArrDelay > 101  " +
          "group by UniqueCarrier order by arrivalDelay")

    df_noColumnBatchesScan.count()

    executionIds =
        snc.sharedState.listener.executionIdToData.keySet.diff(previousExecutionIds)

    executionId = executionIds.head

    val (scanned2, skipped2) =
      findColumnBatchStats(df_allColumnBatchesScan, snc.snappySession, executionId)
    assert(scanned2 == skipped2, "No Column batches should have been scanned")
    assert(skipped2 > 0, "No Column batches should have been scanned")

    // ***Check for the case when some of the column batches are scanned ****
    previousExecutionIds = snc.sharedState.listener.executionIdToData.keySet

    val df_someColumnBatchesScan = snc.sql(
      "select AVG(ArrDelay) arrivalDelay, UniqueCarrier carrier " +
          "from AIRLINE where ArrDelay < 20  " +
          "group by UniqueCarrier order by arrivalDelay")

    df_someColumnBatchesScan.count()

    executionIds =
        snc.sharedState.listener.executionIdToData.keySet.diff(previousExecutionIds)

    executionId = executionIds.head

    val (scanned3, skipped3) =
      findColumnBatchStats(df_allColumnBatchesScan, snc.snappySession, executionId)

    assert(skipped3 > 0, "Some Column batches should have been skipped")
    assert(scanned3 != skipped3, "Some Column batches should have been skipped - comparison")

    // check for StartsWith predicate with MAX/MIN handling

    // first all batches chosen
    previousExecutionIds = snc.sharedState.listener.executionIdToData.keySet

    val df_allColumnBatchesLikeScan = snc.sql(
      "select AVG(ArrDelay) arrivalDelay, UniqueCarrier carrier " +
          "from AIRLINE where UniqueCarrier like 'AA%' " +
          "group by UniqueCarrier order by arrivalDelay")

    var count = df_allColumnBatchesLikeScan.count()
    assert(count == 100, s"Unexpected count = $count, expected 100")

    executionIds =
        snc.sharedState.listener.executionIdToData.keySet.diff(previousExecutionIds)

    executionId = executionIds.head

    val (scanned4, skipped4) =
      findColumnBatchStats(df_allColumnBatchesLikeScan, snc.snappySession, executionId)

    assert(skipped4 == 0, "No Column batches should have been skipped")
    assert(scanned4 > 0, "All Column batches should have been scanned")

    // next some batches skipped
    previousExecutionIds = snc.sharedState.listener.executionIdToData.keySet

    val df_someColumnBatchesLikeScan = snc.sql(
      "select AVG(ArrDelay) arrivalDelay, UniqueCarrier carrier " +
          "from AIRLINE where UniqueCarrier like 'AA1%' " +
          "group by UniqueCarrier order by arrivalDelay")

    count = df_someColumnBatchesLikeScan.count()
    assert(count == 12, s"Unexpected count = $count, expected 12")

    executionIds =
        snc.sharedState.listener.executionIdToData.keySet.diff(previousExecutionIds)

    executionId = executionIds.head

    val (scanned5, skipped5) =
      findColumnBatchStats(df_someColumnBatchesLikeScan, snc.snappySession, executionId)

    assert(skipped5 > 0, "Some Column batches should have been skipped")
    assert(scanned5 != skipped5, "Some Column batches should have been skipped - comparison")

    // last all batches skipped
    previousExecutionIds = snc.sharedState.listener.executionIdToData.keySet

    val df_noColumnBatchesLikeScan = snc.sql(
      "select AVG(ArrDelay) arrivalDelay, UniqueCarrier carrier " +
          "from AIRLINE where UniqueCarrier like 'AA0%' " +
          "group by UniqueCarrier order by arrivalDelay")

    count = df_noColumnBatchesLikeScan.count()
    assert(count == 0, s"Unexpected count = $count, expected 0")

    executionIds =
        snc.sharedState.listener.executionIdToData.keySet.diff(previousExecutionIds)

    executionId = executionIds.head

    val (scanned6, skipped6) =
      findColumnBatchStats(df_noColumnBatchesLikeScan, snc.snappySession, executionId)

    assert(scanned6 == skipped6, "No Column batches should have been returned")
    assert(skipped6 > 0, "No Column batches should have been returned")
  }

  private def findColumnBatchStats(df: DataFrame,
      sc: SnappySession, executionId: Long): (Long, Long) = {

    val metricValues = sc.sharedState.listener.getExecutionMetrics(executionId)
    val a = (sc.sharedState.listener.getRunningExecutions ++
        sc.sharedState.listener.getCompletedExecutions).filter(x => {
      x.executionId == executionId
    })
    val seenid = a.head.accumulatorMetrics.filter(x => {
      x._2.name == "column batches seen"
    }).head._1
    val skippedid = a.head.accumulatorMetrics.filter(x => {
      x._2.name == "column batches skipped by the predicate"
    }).head._1

    (metricValues.filter(_._1 == seenid).head._2.toInt,
        metricValues.filter(_._1 == skippedid).head._2.toInt)
  }
}

case class AirlineData(year: Int, month: Int, dayOfMonth: Int,
    depTime: Int, arrDelay: Int, carrier: String)
