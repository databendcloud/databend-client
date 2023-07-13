/*
 * Copyright 2021 Datafuse Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const assert = require("assert");
const { Client } = require("../index.js");
const { Given, When, Then } = require("@cucumber/cucumber");

const dsn = process.env.TEST_DATABEND_DSN
  ? process.env.TEST_DATABEND_DSN
  : "databend://root:@localhost:8000/default?sslmode=disable";

Given("A new Databend Driver Client", function () {
  this.client = new Client(dsn);
});

Then("Select String {string} should be equal to {string}", async function (input, output) {
  const row = await this.client.queryRow(`SELECT '${input}'`);
  const value = row.values()[0];
  assert.equal(output, value);
});

Then("Select numbers should iterate all rows", async function () {
  let rows = await this.client.queryIter("SELECT number FROM numbers(5)");
  let ret = [];
  let row = await rows.next();
  while (row) {
    ret.push(row.values()[0]);
    row = await rows.next();
  }
  const expected = [0, 1, 2, 3, 4];
  assert.deepEqual(ret, expected);
});

When("Create a test table", async function () {
  await this.client.exec("DROP TABLE IF EXISTS test");
  await this.client.exec(`CREATE TABLE test (
		i64 Int64,
		u64 UInt64,
		f64 Float64,
		s   String,
		s2  String,
		d   Date,
		t   DateTime
    );`);
});

Then("Insert and Select should be equal", async function () {
  await this.client.exec(`INSERT INTO test VALUES
    (-1, 1, 1.0, '1', '1', '2011-03-06', '2011-03-06 06:20:00'),
    (-2, 2, 2.0, '2', '2', '2012-05-31', '2012-05-31 11:20:00'),
    (-3, 3, 3.0, '3', '2', '2016-04-04', '2016-04-04 11:30:00')`
  );
  const rows = await this.client.queryIter("SELECT * FROM test");
  const ret = [];
  let row = await rows.next();
  while (row) {
    ret.push(row.values());
    row = await rows.next();
  }
  const expected = [
    [-1, 1, 1.0, "1", "1", new Date("2011-03-06"), new Date("2011-03-06T06:20:00Z")],
    [-2, 2, 2.0, "2", "2", new Date("2012-05-31"), new Date("2012-05-31T11:20:00Z")],
    [-3, 3, 3.0, "3", "2", new Date("2016-04-04"), new Date("2016-04-04T11:30:00Z")]
  ];
  assert.deepEqual(ret, expected);
});
