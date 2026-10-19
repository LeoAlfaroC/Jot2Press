package io.github.leoalfaroc.jot2press.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import io.github.leoalfaroc.jot2press.TestWorld
import io.github.leoalfaroc.jot2press.data.EntryState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SchedulerTest {
    @get:Rule val folder = TemporaryFolder()
    private val world by lazy { TestWorld(folder.root) }

    @After
    fun close() = world.close()

    private suspend fun sendJobs() = world.jobs(Scheduler.SEND_WORK)
    private suspend fun finished(id: UUID): WorkInfo = world.work.getWorkInfoByIdFlow(id).first { it!!.state.isFinished }!!
    private fun created() = MockResponse.Builder().code(201).body("""{"status":"created","post_id":7}""").build()

    @Test
    fun `with nothing in the queue nothing is scheduled`() = runTest {
        world.queue.saveDraft("Only a draft")
        world.scheduler.sync()
        world.scheduler.reschedule()
        assertTrue(sendJobs().isEmpty())
    }

    @Test
    fun `an entry in its grace window gets a wake-up for the moment the window ends`() = runTest {
        world.queue.saveDraft("Coffee")
        val entry = world.queue.send(graceSeconds = 60)!!
        world.clock.advance(Duration.ofSeconds(20))

        world.scheduler.sync()

        val wakeUp = world.jobs(Scheduler.graceWork(entry.id)).single()
        assertEquals(WorkInfo.State.ENQUEUED, wakeUp.state)
        assertEquals(40_000L, wakeUp.initialDelayMillis)
        assertTrue(sendJobs().isEmpty())
    }

    @Test
    fun `a pending entry schedules the sender for any network, or for unmetered ones with Wi-Fi only`() = runTest {
        world.write("Coffee")
        world.scheduler.sync()
        val any = sendJobs().single()
        assertEquals(WorkInfo.State.ENQUEUED, any.state)
        assertEquals(NetworkType.CONNECTED, any.constraints.requiredNetworkType)

        world.settings.setWifiOnly(true)
        world.scheduler.reschedule()
        val wifi = sendJobs().single { !it.state.isFinished }
        assertEquals(NetworkType.UNMETERED, wifi.constraints.requiredNetworkType)
        assertTrue(wifi.id != any.id)

        world.settings.setWifiOnly(false)
        world.scheduler.reschedule()
        assertEquals(NetworkType.CONNECTED, sendJobs().single { !it.state.isFinished }.constraints.requiredNetworkType)
    }

    @Test
    fun `when the grace window ends the entry is handed to the sender and sent`() = runTest {
        world.connect()
        world.server.enqueue(created())
        world.queue.saveDraft("Coffee")
        val entry = world.queue.send(graceSeconds = 60)!!
        world.scheduler.sync()
        val wakeUp = world.jobs(Scheduler.graceWork(entry.id)).single()

        world.clock.advance(Duration.ofSeconds(60))
        world.driver.setInitialDelayMet(wakeUp.id)
        assertEquals(WorkInfo.State.SUCCEEDED, finished(wakeUp.id).state)
        assertEquals(EntryState.PENDING, world.entries().single().state)

        val send = sendJobs().single()
        world.driver.setAllConstraintsMet(send.id)
        assertEquals(WorkInfo.State.SUCCEEDED, finished(send.id).state)
        assertTrue(world.entries().isEmpty())
        assertEquals(1, world.server.requestCount)
        assertEquals(1, world.widgetRefreshes)
    }

    @Test
    fun `a widget that cannot be redrawn does not change what the send job ends with`() = runTest {
        world.widgetFailure = IllegalStateException("The widget is gone.")
        world.connect()
        world.server.enqueue(created())
        world.write("Coffee")
        world.scheduler.sync()
        val send = sendJobs().single()

        world.driver.setAllConstraintsMet(send.id)

        assertEquals(WorkInfo.State.SUCCEEDED, finished(send.id).state)
        assertTrue(world.entries().isEmpty())
        assertEquals(1, world.widgetRefreshes)
    }

    @Test
    fun `a send that throws leaves the job to be retried`() = runTest {
        world.connect()
        world.write("Coffee")
        world.credentialsFailure = java.io.IOException("The settings could not be read.")
        world.scheduler.sync()
        val send = sendJobs().single()

        world.driver.setAllConstraintsMet(send.id)
        val retried = world.work.getWorkInfoByIdFlow(send.id)
            .first { it!!.runAttemptCount == 1 && it.state != WorkInfo.State.RUNNING }!!

        assertEquals(WorkInfo.State.ENQUEUED, retried.state)
        assertEquals(EntryState.PENDING, world.entries().single().state)
    }

    @Test
    fun `a site that fails leaves the job to be retried`() = runTest {
        world.connect()
        world.server.enqueue(MockResponse.Builder().code(500).body("").build())
        world.write("Coffee")
        world.scheduler.sync()
        val send = sendJobs().single()

        world.driver.setAllConstraintsMet(send.id)
        val retried = world.work.getWorkInfoByIdFlow(send.id)
            .first { it!!.runAttemptCount == 1 && it.state != WorkInfo.State.RUNNING }!!

        assertEquals(WorkInfo.State.ENQUEUED, retried.state)
        assertEquals(EntryState.PENDING, world.entries().single().state)
    }

    @Test
    fun `an entry that becomes pending while the sender runs does not cancel it`() = runTest {
        world.connect()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        world.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(30, TimeUnit.SECONDS)
                return created()
            }
        }
        world.write("First")
        world.scheduler.sync()
        val running = sendJobs().single()
        world.driver.setAllConstraintsMet(running.id)
        assertTrue(arrived.await(30, TimeUnit.SECONDS))

        world.write("Second")
        world.scheduler.sync()
        world.scheduler.sync()

        val jobs = sendJobs()
        assertEquals(2, jobs.size)
        assertEquals(WorkInfo.State.RUNNING, jobs.single { it.id == running.id }.state)
        assertEquals(WorkInfo.State.BLOCKED, jobs.single { it.id != running.id }.state)

        release.countDown()
        assertEquals(WorkInfo.State.SUCCEEDED, finished(running.id).state)
        assertTrue(world.entries().isEmpty())
    }
}
