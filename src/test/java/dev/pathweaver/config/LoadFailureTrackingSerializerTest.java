package dev.pathweaver.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LoadFailureTrackingSerializerTest {
    @Test
    void deserializeFailureSignalSurvivesDelegateException() {
        AtomicBoolean failed = new AtomicBoolean();
        ConfigSerializer<TestConfig> malformed = new ConfigSerializer<>() {
            @Override public void serialize(TestConfig config) {}
            @Override public TestConfig deserialize() throws SerializationException {
                throw new SerializationException(new IllegalStateException("malformed JSON"));
            }
            @Override public TestConfig createDefault() { return new TestConfig(); }
        };
        var serializer = new LoadFailureTrackingSerializer<>(malformed, failed);

        assertThrows(ConfigSerializer.SerializationException.class, serializer::deserialize);
        assertTrue(failed.get(), "the wrapper must retain the failure after Cloth catches it");
    }

    static final class TestConfig implements ConfigData {
        public boolean asyncEnabled = true;
    }
}
