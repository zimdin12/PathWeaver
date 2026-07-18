package dev.pathweaver.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;

import java.util.concurrent.atomic.AtomicBoolean;

/** Retains a deserialize failure signal after Cloth replaces the failed load with defaults. */
public final class LoadFailureTrackingSerializer<T extends ConfigData> implements ConfigSerializer<T> {
    private final ConfigSerializer<T> delegate;
    private final AtomicBoolean loadFailed;

    public LoadFailureTrackingSerializer(ConfigSerializer<T> delegate, AtomicBoolean loadFailed) {
        this.delegate = delegate;
        this.loadFailed = loadFailed;
    }

    @Override
    public void serialize(T config) throws SerializationException {
        delegate.serialize(config);
    }

    @Override
    public T deserialize() throws SerializationException {
        try {
            return delegate.deserialize();
        } catch (SerializationException failure) {
            loadFailed.set(true);
            throw failure;
        }
    }

    @Override
    public T createDefault() {
        return delegate.createDefault();
    }
}
