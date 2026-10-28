package com.butterflyatlas.app.window;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** A second launch opens a window on the running copy, waits while it closes, or says it does not answer (design §4, audit 4). */
class SecondLaunchTest {

    @TempDir
    Path config;

    private InstancePointer pointer;
    private final AtomicLong nanos = new AtomicLong();
    private final List<String> asked = new ArrayList<>();
    private final List<SecondLaunch.Reply> replies = new ArrayList<>();
    private final List<Supplier<URI>> opened = new ArrayList<>();
    private Runnable onSleep = () -> { };

    @BeforeEach
    void pointer() {
        pointer = new InstancePointer(config);
    }

    private SecondLaunch launch() {
        return new SecondLaunch(pointer, (port, key) -> {
            asked.add(port + ":" + key);
            SecondLaunch.Reply reply = replies.isEmpty() ? null : replies.size() == 1 ? replies.get(0) : replies.remove(0);
            if (reply == null) {
                throw new IOException("Connection refused");
            }
            return reply;
        }, nanos::get, millis -> {
            nanos.addAndGet(millis * 1_000_000L);
            onSleep.run();
        });
    }

    private void running() {
        pointer.write(InstancePointer.Info.ofThisProcess(7878, "the-key"));
    }

    @Test
    void withNoPointerNothingIsRunning() {
        assertThat(launch().run(opened::add)).isEqualTo(SecondLaunch.Outcome.NOT_RUNNING);
        assertThat(asked).isEmpty();
    }

    @Test
    void aPointerWhoseProcessIsGoneIsStaleAndIsRemovedSoTheStartGoesOn() {
        pointer.write(new InstancePointer.Info(7878, Long.MAX_VALUE - 1, 0, "dead"));

        assertThat(launch().run(opened::add)).isEqualTo(SecondLaunch.Outcome.NOT_RUNNING);

        assertThat(pointer.read()).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void aRunningCopyGivesAKeyAndTheWindowOpensOnItWithoutANotice() {
        running();
        replies.add(new SecondLaunch.Reply(200, "{\"launch\":\"one-time_KEY-1\"}"));

        SecondLaunch.Outcome outcome = launch().run(opened::add);

        assertThat(outcome).isEqualTo(SecondLaunch.Outcome.OPENED);
        assertThat(asked).containsExactly("7878:the-key");
        assertThat(opened).singleElement().satisfies(address -> assertThat(address.get())
                .isEqualTo(URI.create("http://127.0.0.1:7878/launch?k=one-time_KEY-1")));
    }

    @Test
    void aFreshKeyIsAskedForEachTimeTheAddressIsNeededAgain() {
        running();
        replies.add(new SecondLaunch.Reply(200, "{\"launch\":\"first\"}"));
        replies.add(new SecondLaunch.Reply(200, "{\"launch\":\"second\"}"));
        launch().run(opened::add);
        Supplier<URI> address = opened.get(0);

        assertThat(address.get().toString()).contains("k=first");
        assertThat(address.get().toString()).contains("k=second");
    }

    @Test
    void aCopyThatIsClosingIsWaitedForAndThenTheStartGoesOn() {
        running();
        replies.add(new SecondLaunch.Reply(503, "{\"error\":{\"code\":\"closing\"}}"));
        SecondLaunch second = new SecondLaunch(pointer, (port, key) -> {
            pointer.removeIfOurs("the-key"); // the quitting copy has taken its pointer away
            return replies.get(0);
        }, nanos::get, millis -> nanos.addAndGet(millis * 1_000_000L));

        assertThat(second.run(opened::add)).isEqualTo(SecondLaunch.Outcome.NOT_RUNNING);
        assertThat(opened).isEmpty();
    }

    @Test
    void aCopyThatNeverAnswersIsTriedForThirtySecondsThenGivenUpOn() {
        running(); // alive (this process), but no answer: starting, or stuck

        SecondLaunch.Outcome outcome = launch().run(opened::add);

        assertThat(outcome).isEqualTo(SecondLaunch.Outcome.NOT_ANSWERING);
        assertThat(nanos.get()).isBetween(30_000_000_000L, 31_000_000_000L);
        assertThat(asked.size()).isGreaterThan(10);
        assertThat(opened).isEmpty();
    }

    @Test
    void aCopyThatAnswersLateIsStillFoundWithinTheThirtySeconds() {
        running();
        SecondLaunch second = new SecondLaunch(pointer, (port, key) -> {
            if (nanos.get() < 5_000_000_000L) {
                throw new IOException("Connection refused"); // still starting
            }
            return new SecondLaunch.Reply(200, "{\"launch\":\"late\"}");
        }, nanos::get, millis -> nanos.addAndGet(millis * 1_000_000L));

        assertThat(second.run(opened::add)).isEqualTo(SecondLaunch.Outcome.OPENED);
    }

    @Test
    void aWrongKeyAnswerIsNotAnAnswerEither() {
        running();
        replies.add(new SecondLaunch.Reply(401, "{\"error\":{\"code\":\"no_session\"}}"));

        assertThat(launch().run(opened::add)).isEqualTo(SecondLaunch.Outcome.NOT_ANSWERING);
    }

    @Test
    void aCopyThatIsStillStartingIsWaitedForUntilItsPointerAppearsThenAWindowOpensOnIt() {
        replies.add(new SecondLaunch.Reply(200, "{\"launch\":\"the-launch-key\"}"));
        int[] sleeps = {0};
        onSleep = () -> {
            if (++sleeps[0] == 5) {
                running(); // the first copy's library is open now: it writes its pointer
            }
        };

        assertThat(launch().run(opened::add, true)).isEqualTo(SecondLaunch.Outcome.OPENED);

        assertThat(sleeps[0]).isEqualTo(5);
        assertThat(opened).hasSize(1);
    }

    @Test
    void aCopyThatStaysStartingForThirtySecondsIsNotAnswering() {
        pointer.write(new InstancePointer.Info(7878, Long.MAX_VALUE - 1, 0, "left by a crash"));

        assertThat(launch().run(opened::add, true)).isEqualTo(SecondLaunch.Outcome.NOT_ANSWERING);

        assertThat(nanos.get()).isGreaterThanOrEqualTo(30_000_000_000L);
        assertThat(opened).isEmpty();
        assertThat(asked).isEmpty();
    }
}
