/*
 * Copyright 2021 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.kotlin.test.common.notifications

import co.touchlab.stately.concurrency.AtomicInt
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.entities.Sample
import io.realm.kotlin.entities.list.RealmListContainer
import io.realm.kotlin.entities.list.listTestSchema
import io.realm.kotlin.ext.query
import io.realm.kotlin.notifications.InitialResults
import io.realm.kotlin.notifications.ListChangeSet.Range
import io.realm.kotlin.notifications.ResultsChange
import io.realm.kotlin.notifications.UpdatedResults
import io.realm.kotlin.query.find
import io.realm.kotlin.test.common.OBJECT_VALUES
import io.realm.kotlin.test.common.OBJECT_VALUES2
import io.realm.kotlin.test.common.OBJECT_VALUES3
import io.realm.kotlin.test.common.utils.FlowableTests
import io.realm.kotlin.test.common.utils.assertIsChangeSet
import io.realm.kotlin.test.platform.PlatformUtils
import io.realm.kotlin.test.util.receiveOrFail
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RealmResultsNotificationsTests : FlowableTests {

    lateinit var tmpDir: String
    lateinit var configuration: RealmConfiguration
    lateinit var realm: Realm

    @BeforeTest
    fun setup() {
        tmpDir = PlatformUtils.createTempDir()
        configuration = RealmConfiguration.Builder(schema = setOf(Sample::class) + listTestSchema)
            .directory(tmpDir)
            .build()
        realm = Realm.open(configuration)
    }

    @AfterTest
    fun tearDown() {
        if (this::realm.isInitialized && !realm.isClosed()) {
            realm.close()
        }
        PlatformUtils.deleteTempDir(tmpDir)
    }

    @Test
    override fun initialElement() {
        runBlocking {
            val c = Channel<ResultsChange<Sample>>(1)
            val observer = async {
                realm.query<Sample>()
                    .asFlow()
                    .collect {
                        c.trySend(it)
                    }
            }

            c.receiveOrFail().let { resultsChange ->
                assertIs<InitialResults<*>>(resultsChange)
                assertTrue(resultsChange.list.isEmpty())
            }

            observer.cancel()
            c.close()
        }
    }

    @Test
    @Suppress("LongMethod")
    override fun asFlow() {
        val dataset1 = OBJECT_VALUES
        val dataset2 = OBJECT_VALUES2
        val dataset3 = OBJECT_VALUES3

        runBlocking {
            val c = Channel<ResultsChange<*>>(capacity = 1)
            val observer = async {
                realm.query<RealmListContainer>()
                    .sort("stringField")
                    .asFlow()
                    .collect {
                        c.trySend(it)
                    }
            }

            // Assertion after empty list is emitted
            c.receiveOrFail().let { resultsChange ->
                assertIs<InitialResults<*>>(resultsChange)

                assertNotNull(resultsChange.list)
                assertEquals(0, resultsChange.list.size)
            }

            // Assert a single range is reported
            //
            // objectListField = [C, D, E, F]
            realm.writeBlocking {
                dataset2.forEach {
                    copyToRealm(it)
                }
            }

            c.receiveOrFail().let { resultsChange ->
                assertIs<UpdatedResults<*>>(resultsChange)

                assertNotNull(resultsChange.list)
                assertEquals(dataset2.size, resultsChange.list.size)

                assertIsChangeSet(
                    (resultsChange as UpdatedResults<*>),
                    insertRanges = arrayOf(
                        Range(0, 4)
                    )
                )
            }

            // Assert multiple ranges are reported
            //
            // objectListField = [<A, B>, C, D, E, F, <G, H>]
            realm.writeBlocking {
                (dataset1 + dataset3).forEach {
                    copyToRealm(it)
                }
            }

            c.receiveOrFail().let { resultsChange ->
                assertIs<UpdatedResults<*>>(resultsChange)

                assertNotNull(resultsChange.list)
                assertEquals(dataset1.size + dataset2.size + dataset3.size, resultsChange.list.size)

                assertIsChangeSet(
                    (resultsChange as UpdatedResults<*>),
                    insertRanges = arrayOf(
                        Range(0, 2),
                        Range(6, 2)
                    )
                )
            }

            // Assert multiple ranges are deleted
            //
            // objectListField = [<A, B>, C, D, E, F, <G, H>]
            realm.writeBlocking {
                (dataset1 + dataset3).forEach { element ->
                    delete(
                        query<RealmListContainer>("stringField = $0", element.stringField)
                            .first()
                            .find()!!
                    )
                }
            }

            c.receiveOrFail().let { resultsChange ->
                assertIs<UpdatedResults<*>>(resultsChange)

                assertNotNull(resultsChange.list)
                assertEquals(dataset2.size, resultsChange.list.size)

                assertIsChangeSet(
                    (resultsChange as UpdatedResults<*>),
                    deletionRanges = arrayOf(
                        Range(0, 2),
                        Range(6, 2)
                    )
                )
            }

            // Assert a single range is deleted
            //
            // objectListField = [<A, B>]
            realm.writeBlocking {
                dataset2.forEach { element ->
                    delete(
                        query<RealmListContainer>("stringField = $0", element.stringField)
                            .first()
                            .find()!!
                    )
                }
            }

            c.receiveOrFail().let { resultsChange ->
                assertIs<UpdatedResults<*>>(resultsChange)

                assertNotNull(resultsChange.list)
                assertTrue(resultsChange.list.isEmpty())

                assertIsChangeSet(
                    (resultsChange as UpdatedResults<*>),
                    deletionRanges = arrayOf(
                        Range(0, 4)
                    )
                )
            }

            // Add some values to change
            //
            // objectListField = [<C, D, E, F>]
            realm.writeBlocking {
                dataset2.forEach {
                    copyToRealm(it)
                }
            }

            c.receiveOrFail().let { resultsChange ->
                assertIs<UpdatedResults<*>>(resultsChange)

                assertNotNull(resultsChange.list)
                assertEquals(dataset2.size, resultsChange.list.size)
            }

            // Change contents of two ranges of values
            //
            // objectListField = [<A>, <B>, E, <F>]
            realm.writeBlocking {
                query<RealmListContainer>()
                    .sort("stringField")
                    .find { queriedList ->
                        queriedList[0].stringField = "A"
                        queriedList[1].stringField = "B"
                        queriedList[3].stringField = "F"
                    }
            }

            c.receiveOrFail().let { resultsChange ->
                assertIs<UpdatedResults<*>>(resultsChange)

                assertNotNull(resultsChange.list)
                assertEquals(dataset2.size, resultsChange.list.size)

                assertIsChangeSet(
                    (resultsChange as UpdatedResults<*>),
                    changesRanges = arrayOf(
                        Range(0, 2),
                        Range(3, 1),
                    )
                )
            }

            observer.cancel()
            c.close()
        }
    }

    @Test
    override fun cancelAsFlow() {
        runBlocking {
            val c1 = Channel<ResultsChange<Sample>>(1)
            val c2 = Channel<ResultsChange<Sample>>(1)

            realm.write {
                copyToRealm(Sample().apply { stringField = "Bar" })
            }

            val observer1 = async {
                realm.query<Sample>()
                    .asFlow()
                    .collect {
                        c1.trySend(it)
                    }
            }
            val observer2 = async {
                realm.query<Sample>()
                    .asFlow()
                    .collect {
                        c2.trySend(it)
                    }
            }

            c1.receiveOrFail().let { resultsChange ->
                assertIs<InitialResults<*>>(resultsChange)
                assertEquals(1, resultsChange.list.size)
            }
            c2.receiveOrFail().let { resultsChange ->
                assertIs<InitialResults<*>>(resultsChange)
                assertEquals(1, resultsChange.list.size)
            }

            observer1.cancel()

            realm.write {
                copyToRealm(Sample().apply { stringField = "Baz" })
            }

            c2.receiveOrFail().let { resultsChange ->
                assertIs<UpdatedResults<*>>(resultsChange)
                assertEquals(2, resultsChange.list.size)
            }
            assertTrue(c1.isEmpty)
            observer2.cancel()
            c1.close()
            c2.close()
        }
    }

    @Test
    @Ignore // FIXME Not correctly implemented yet
    override fun closeRealmInsideFlowThrows() {
        runBlocking {
            val c = Channel<Int>(capacity = 1)
            val counter = AtomicInt(0)
            val observer1 = async {
                realm.query<Sample>()
                    .asFlow()
                    .collect {
                        when (counter.incrementAndGet()) {
                            1 -> c.trySend(it.list.size)
                            2 -> {
                                realm.close()
                                c.trySend(-1)
                                println("realm closed")
                            }
                        }
                    }
            }
            val observer2 = async {
                realm.query<Sample>()
                    .asFlow()
                    .collect {
                        println(it.list.first().stringField)
                        println("$it -> ${realm.isClosed()}")
                    }
            }
            realm.write {
                copyToRealm(Sample().apply { stringField = "Foo" })
            }
            assertEquals(1, c.receiveOrFail())
            realm.write {
                copyToRealm(Sample().apply { stringField = "Bar" })
            }
            assertEquals(-1, c.receiveOrFail())
            observer1.cancel()
            observer2.cancel()
            c.close()
        }
    }

    @Test
    override fun closingRealmDoesNotCancelFlows() {
        runBlocking {
            val c = Channel<Int>(capacity = 1)
            val observer = async {
                realm.query<Sample>()
                    .asFlow()
                    .filterNot {
                        it.list.isEmpty()
                    }.collect {
                        c.send(it.list.size)
                    }
                fail("Flow should not be canceled.")
            }
            realm.write {
                copyToRealm(Sample().apply { stringField = "Foo" })
            }
            assertEquals(1, c.receiveOrFail())
            realm.close()
            observer.cancel()
            c.close()
        }
    }
}
