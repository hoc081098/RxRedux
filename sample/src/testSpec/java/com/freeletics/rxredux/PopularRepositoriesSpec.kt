package com.freeletics.rxredux

import com.freeletics.rxredux.businesslogic.pagination.PaginationStateMachine
import io.reactivex.Observable
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert
import timber.log.Timber
import java.util.concurrent.TimeUnit


/**
 * Abstraction layer that shows what a user can do on the screen
 */
interface Screen {
    /**
     * Scroll the list to the item at position
     */
    fun scrollToEndOfList()

    /**
     * Action on the screen: Clicks on the retry button to retry loading the first page
     */
    fun retryLoadingFirstPage()

    /**
     * Launches the screen.
     * After having this called, the screen is visible
     */
    fun loadFirstPage()
}

/**
 * Can record states over time.
 */
interface StateRecorder {
    /**
     * Observable of recorded States
     */
    fun renderedStates(): Observable<PaginationStateMachine.State>
}

/**
 * Keep the whole history of all states over time
 */
class StateHistory(private val stateRecorder: StateRecorder) {

    /**
     * All states that has been captured and asserted in an `on`cl
     */
    private var stateHistory: List<PaginationStateMachine.State> = emptyList()

    /**
     * Waits until the next state is rendered and then retruns a [StateHistorySnapshot]
     * or if a timeout happens then a TimeOutException will be thrown
     */
    internal fun waitUntilNextRenderedState(): StateHistorySnapshot {
        val recordedStates = stateRecorder.renderedStates()
            .take(stateHistory.size + 1L)
            .toList()
            .timeout(30, TimeUnit.SECONDS)
            .blockingGet()

        val history = stateHistory
        stateHistory = recordedStates

        return StateHistorySnapshot(
            actualRecordedStates = recordedStates,
            verifiedHistory = history
        )
    }

    /**
     * A Snapshot in time
     */
    internal data class StateHistorySnapshot(
        /**
         * The actual full recorded history of states
         */
        val actualRecordedStates: List<PaginationStateMachine.State>,

        /**
         * full history of all states that we have already verified / validated and
         * are sure that this list of states is correct
         */
        val verifiedHistory: List<PaginationStateMachine.State>
    )
}


private data class Given(
    private val screen: Screen,
    private val stateHistory: StateHistory,
    private val composedMessage: String
) {

    inner class On(private val composedMessage: String) {

        inner class It(private val composedMessage: String) {

            internal fun renderedState(expectedState: PaginationStateMachine.State) {

                val (recordedStates, verifiedHistory) = stateHistory.waitUntilNextRenderedState()
                val expectedStates = verifiedHistory + expectedState
                Assert.assertEquals(
                    composedMessage,
                    expectedStates,
                    recordedStates
                )
                Timber.d("✅ $composedMessage")

            }
        }

        infix fun String.byRendering(expectedState: PaginationStateMachine.State) {
            val message = this
            val it = It("$composedMessage *IT* $message")
            it.renderedState(expectedState)
        }
    }

    fun on(message: String, block: On.() -> Unit) {
        val on = On("*GIVEN* $composedMessage *ON* $message")
        on.block()
    }
}

/**
 * A simple holder object for all required configuration
 */
data class ScreenConfig(
    val mockWebServer: MockWebServer
)

class PopularRepositoriesSpec(
    private val screen: Screen,
    private val stateHistory: StateHistory,
    private val config: ScreenConfig
) {

    private fun given(message: String, block: Given.() -> Unit) {
        val given = Given(screen, stateHistory, message)
        given.block()
    }

    fun runTests() {
        given("the Repositories List Screen") {

            val server = config.mockWebServer
            val connectionErrorMessage = "Failed to connect to /127.0.0.1:$MOCK_WEB_SERVER_PORT"

            on("device is offline") {

                server.shutdown()
                screen.loadFirstPage()
                "shows loading first page" byRendering PaginationStateMachine.State.LoadingFirstPageState
                "shows error loading first page" byRendering
                        PaginationStateMachine.State.ErrorLoadingFirstPageState(
                            connectionErrorMessage
                        )
            }

            on("device is online again and user clicks retry loading first page") {

                server.enqueue200(FIRST_PAGE)
                server.start(MOCK_WEB_SERVER_PORT)

                screen.retryLoadingFirstPage()

                "shows loading" byRendering PaginationStateMachine.State.LoadingFirstPageState

                "shows first page" byRendering PaginationStateMachine.State.ShowContentState(
                    items = FIRST_PAGE,
                    page = 1
                )

            }

            on("scrolling to the end of the first page") {

                server.enqueue200(SECOND_PAGE)
                screen.scrollToEndOfList()
                "shows loading next page" byRendering
                        PaginationStateMachine.State.ShowContentAndLoadNextPageState(
                            items = FIRST_PAGE,
                            page = 1
                        )

                "shows next page content" byRendering
                        PaginationStateMachine.State.ShowContentState(
                            items = FIRST_PAGE + SECOND_PAGE,
                            page = 2
                        )
            }

            on("device is offline again and scrolling to end of second page") {

                server.shutdown()
                screen.scrollToEndOfList()

                "shows loading next page" byRendering
                        PaginationStateMachine.State.ShowContentAndLoadNextPageState(
                            items = FIRST_PAGE + SECOND_PAGE,
                            page = 2
                        )

                "shows error info for few seconds on top of the list of items" byRendering
                        PaginationStateMachine.State.ShowContentAndLoadNextPageErrorState(
                            items = FIRST_PAGE + SECOND_PAGE,
                            page = 2,
                            errorMessage = connectionErrorMessage
                        )

                "hides error information and shows items only" byRendering
                        PaginationStateMachine.State.ShowContentState(
                            items = FIRST_PAGE + SECOND_PAGE,
                            page = 2

                        )
            }
        }
    }
}
