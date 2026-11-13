package io.taiyiflow.sdk.worker;

interface TaskControl {
    void pause() throws Exception;

    void resume() throws Exception;

    void cancel() throws Exception;

    void invalidate();
}
