/*
 * Copyright 2022 Realm Inc.
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

import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.entities.set.RealmSetContainer
import io.realm.kotlin.notifications.DeletedSet
import io.realm.kotlin.notifications.InitialSet
import io.realm.kotlin.notifications.SetChange
import io.realm.kotlin.notifications.UpdatedSet
import io.realm.kotlin.test.common.SET_OBJECT_VALUES
import io.realm.kotlin.test.common.SET_OBJECT_VALUES2
import io.realm.kotlin.test.common.SET_OBJECT_VALUES3
import io.realm.kotlin.test.common.utils.RealmEntityNotificationTests
import io.realm.kotlin.test.platform.PlatformUtils
import io.realm.kotlin.test.util.receiveOrFail
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class RealmSetNotificationsTests : RealmEntityNotificationTests {

    lateinit var tmpDir: String
    lateinit var configuration: RealmConfiguration
    lateinit var realm: Realm

    @BeforeTest
    fun setup() {
        tmpDir = PlatformUtils.createTempDir()
        configuration = RealmConfiguration.Builder(schema = setOf(RealmSetContainer::class))
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
        val dataSet = SET_OBJECT_VALUES

        val container = realm.writeBlocking {
            copyToRealm(RealmSetContainer()).also {
                it.objectSetField.addAll(dataSet)
            }
        }

        runBlocking {
            val channel = Channel<SetChange<*>>(capacity = 1)
            val observer = async {
                container.objectSetField
                    .asFlow()
                    .collect { flowSet ->
                        channel.send(flowSet)
                    }
            }

            // Assertion after empty set is emitted
            channel.receiveOrFail().let { setChange ->
                assertIs<InitialSet<*>>(setChange)

                assertNotNull(setChange.set)
                assertEquals(dataSet.size, setChange.set.size)
            }

            observer.cancel()
            channel.close()
        }
    }

    @Test
    @Suppress("LongMethod")
    override fun asFlow() {
        val dataset = SET_OBJECT_VALUES
        val dataset2 = SET_OBJECT_VALUES2
        val dataset3 = SET_OBJECT_VALUES3

        val container = realm.writeBlocking {
            // Create an empty container with empty sets
            copyToRealm(RealmSetContainer())
        }

        runBlocking {
            val channel = Channel<SetChange<*>>(capacity = 1)
            val observer = async {
                container.objectSetField
                    .asFlow()
                    .collect { flowSet ->
                        channel.send(flowSet)
                    }
            }

            channel.receive().let {
                assertIs<InitialSet<*>>(it)
            }

            // Assert a single insertion is reported
            realm.writeBlocking {
                val queriedContainer = findLatest(container)
                val queriedSet = queriedContainer!!.objectSetField
                queriedSet.addAll(dataset)
            }

            channel.receiveOrFail().let { setChange ->
                assertIs<UpdatedSet<*>>(setChange)

                assertNotNull(setChange.set)
                assertEquals(dataset.size, setChange.set.size)
                assertEquals(dataset.size, setChange.insertions)
                assertEquals(0, setChange.deletions)
            }

            // Assert notification on removal of elements
            realm.writeBlocking {
                val queriedContainer = findLatest(container)!!
                val queriedSet = queriedContainer.objectSetField

                // We cannot just remove a RealmObject as equality isn't done at a structural level
                // so calling queriedSet.removeAll(dataset) won't work
                // Use iterator.remove() instead to remove the last element
                val iterator = queriedSet.iterator()
                while (iterator.hasNext()) {
                    iterator.next()
                }
                iterator.remove()
            }

            channel.receiveOrFail().let { setChange ->
                assertIs<UpdatedSet<*>>(setChange)

                assertNotNull(setChange.set)
                assertEquals(dataset.size - 1, setChange.set.size)
                assertEquals(1, setChange.deletions)
                assertEquals(0, setChange.insertions)
            }

            observer.cancel()
            channel.close()
        }
    }

    @Test
    override fun cancelAsFlow() {
        runBlocking {
            val values = SET_OBJECT_VALUES
            val container = realm.write {
                copyToRealm(RealmSetContainer())
            }
            val channel1 = Channel<SetChange<*>>(1)
            val channel2 = Channel<SetChange<*>>(1)
            val observer1 = async {
                container.objectSetField
                    .asFlow()
                    .collect { flowSet ->
                        channel1.trySend(flowSet)
                    }
            }
            val observer2 = async {
                container.objectSetField
                    .asFlow()
                    .collect { flowSet ->
                        channel2.trySend(flowSet)
                    }
            }

            // Ignore first emission with empty sets
            channel1.receiveOrFail()
            channel2.receiveOrFail()

            // Trigger an update
            realm.write {
                val queriedContainer = findLatest(container)
                queriedContainer!!.objectSetField.addAll(SET_OBJECT_VALUES)
            }
            assertEquals(SET_OBJECT_VALUES.size, channel1.receiveOrFail().set.size)
            assertEquals(SET_OBJECT_VALUES.size, channel2.receiveOrFail().set.size)

            // Cancel observer 1
            observer1.cancel()

            // Trigger another update
            realm.write {
                val queriedContainer = findLatest(container)
                queriedContainer!!.objectSetField
                    .add(copyToRealm(RealmSetContainer().apply { stringField = "C" }))
            }

            // Check channel 1 didn't receive the update
            assertEquals(SET_OBJECT_VALUES.size + 1, channel2.receiveOrFail().set.size)
            assertTrue(channel1.isEmpty)

            observer2.cancel()
            channel1.close()
            channel2.close()
        }
    }

    @Test
    override fun deleteEntity() {
        runBlocking {
            // Freeze values since native complains if we reference a package-level defined variable
            // inside a write block
            val values = SET_OBJECT_VALUES
            val channel1 = Channel<SetChange<*>>(capacity = 1)
            val channel2 = Channel<Boolean>(capacity = 1)
            val container = realm.write {
                copyToRealm(
                    RealmSetContainer().apply {
                        objectSetField.addAll(values)
                    }
                )
            }
            val observer = async {
                container.objectSetField
                    .asFlow()
                    .onCompletion {
                        // Signal completion
                        channel2.send(true)
                    }.collect { flowSet ->
                        channel1.send(flowSet)
                    }
            }

            // Assert container got populated correctly
            channel1.receiveOrFail().let { setChange ->
                assertIs<InitialSet<*>>(setChange)

                assertNotNull(setChange.set)
                assertEquals(SET_OBJECT_VALUES.size, setChange.set.size)
            }

            // Now delete owner
            realm.write {
                delete(findLatest(container)!!)
            }

            channel1.receiveOrFail().let { setChange ->
                assertIs<DeletedSet<*>>(setChange)
                assertTrue(setChange.set.isEmpty())
            }
            // Wait for flow completion
            assertTrue(channel2.receiveOrFail())

            observer.cancel()
            channel1.close()
        }
    }

    @Test
    override fun asFlowOnDeleteEntity() {
        runBlocking {
            val container = realm.write { copyToRealm(RealmSetContainer()) }
            val mutex = Mutex(true)
            val flow = async {
                container.stringSetField.asFlow().first {
                    mutex.unlock()
                    it is DeletedSet<*>
                }
            }

            // Await that flow is actually running
            mutex.lock()
            // And delete containing entity
            realm.write { delete(findLatest(container)!!) }

            // Await that notifier has signalled the deletion so we are certain that the entity
            // has been deleted
            withTimeout(10.seconds) {
                flow.await()
            }

            // Verify that a flow on the deleted entity will signal a deletion and complete gracefully
            withTimeout(10.seconds) {
                container.stringSetField.asFlow().collect {
                    assertIs<DeletedSet<*>>(it)
                }
            }
        }
    }

    @Test
    @Ignore // FIXME Wait for https://github.com/realm/realm-kotlin/pull/300 to be merged before fleshing this out
    override fun closeRealmInsideFlowThrows() {
        TODO("Waiting for RealmSet support")
    }

    @Test
    override fun closingRealmDoesNotCancelFlows() {
        runBlocking {
            val channel = Channel<SetChange<*>>(capacity = 1)
            val container = realm.write {
                copyToRealm(RealmSetContainer())
            }
            val observer = async {
                container.objectSetField
                    .asFlow()
                    .collect { flowSet ->
                        channel.trySend(flowSet)
                    }
                fail("Flow should not be canceled.")
            }

            assertTrue(channel.receiveOrFail().set.isEmpty())

            realm.close()
            observer.cancel()
            channel.close()
        }
    }
}
