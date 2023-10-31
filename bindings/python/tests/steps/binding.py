# Copyright 2021 Datafuse Labs
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os

from behave import given, when, then
from behave.api.async_step import async_run_until_complete
import databend_driver


@given("A new Databend Driver Client")
@async_run_until_complete
async def _(context):
    dsn = os.getenv(
        "TEST_DATABEND_DSN", "databend+http://root:root@localhost:8000/?sslmode=disable"
    )
    client = databend_driver.AsyncDatabendClient(dsn)
    context.conn = await client.get_conn()


@when("Create a test table")
@async_run_until_complete
async def _(context):
    await context.conn.exec("DROP TABLE IF EXISTS test")
    await context.conn.exec(
        """
        CREATE TABLE test (
            i64 Int64,
            u64 UInt64,
            f64 Float64,
            s   String,
            s2  String,
            d   Date,
            t   DateTime
        )
        """
    )


@then("Select string {input} should be equal to {output}")
@async_run_until_complete
async def _(context, input, output):
    row = await context.conn.query_row(f"SELECT '{input}'")
    value = row.values()[0]
    assert output == value


@then("Select numbers should iterate all rows")
@async_run_until_complete
async def _(context):
    rows = await context.conn.query_iter("SELECT number FROM numbers(5)")
    ret = []
    async for row in rows:
        ret.append(row.values()[0])
    expected = [0, 1, 2, 3, 4]
    assert ret == expected


@then("Insert and Select should be equal")
@async_run_until_complete
async def _(context):
    await context.conn.exec(
        """
        INSERT INTO test VALUES
            (-1, 1, 1.0, '1', '1', '2011-03-06', '2011-03-06 06:20:00'),
            (-2, 2, 2.0, '2', '2', '2012-05-31', '2012-05-31 11:20:00'),
            (-3, 3, 3.0, '3', '2', '2016-04-04', '2016-04-04 11:30:00')
        """
    )
    rows = await context.conn.query_iter("SELECT * FROM test")
    ret = []
    async for row in rows:
        ret.append(row.values())
    expected = [
        (-1, 1, 1.0, "1", "1", "2011-03-06", "2011-03-06 06:20:00"),
        (-2, 2, 2.0, "2", "2", "2012-05-31", "2012-05-31 11:20:00"),
        (-3, 3, 3.0, "3", "2", "2016-04-04", "2016-04-04 11:30:00"),
    ]
    assert ret == expected


@then("Stream load and Select should be equal")
@async_run_until_complete
async def _(context):
    values = [
        ["-1", "1", "1.0", "1", "1", "2011-03-06", "2011-03-06T06:20:00Z"],
        ["-2", "2", "2.0", "2", "2", "2012-05-31", "2012-05-31T11:20:00Z"],
        ["-3", "3", "3.0", "3", "2", "2016-04-04", "2016-04-04T11:30:00Z"],
    ]
    progress = await context.conn.stream_load("INSERT INTO test VALUES", values)
    assert progress.write_rows == 3
    assert progress.write_bytes == 185

    rows = await context.conn.query_iter("SELECT * FROM test")
    ret = []
    async for row in rows:
        ret.append(row.values())
    expected = [
        (-1, 1, 1.0, "1", "1", "2011-03-06", "2011-03-06 06:20:00"),
        (-2, 2, 2.0, "2", "2", "2012-05-31", "2012-05-31 11:20:00"),
        (-3, 3, 3.0, "3", "2", "2016-04-04", "2016-04-04 11:30:00"),
    ]
    print("==>", ret)
    assert ret == expected
