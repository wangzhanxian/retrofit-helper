package com.xcheng.retrofit;

import android.support.annotation.Nullable;
import android.support.annotation.UiThread;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Executor;

import okhttp3.Request;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

/**
 * just for android post UI thread
 */
public final class ExecutorCallAdapterFactory extends CallAdapter.Factory {

    public static final CallAdapter.Factory INSTANCE = new ExecutorCallAdapterFactory();

    private ExecutorCallAdapterFactory() {
    }

    /**
     * Extract the raw class type from {@code type}. For example, the type representing
     * {@code List<? extends Runnable>} returns {@code List.class}.
     */
    public static Class<?> getRawType(Type type) {
        return CallAdapter.Factory.getRawType(type);
    }

    @Override
    public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations, Retrofit retrofit) {
        if (getRawType(returnType) != Call2.class) {
            return null;
        }
        if (!(returnType instanceof ParameterizedType)) {
            throw new IllegalArgumentException(
                    "Call return type must be parameterized as Call2<Foo> or Call2<? extends Foo>");
        }
        final Type responseType = getParameterUpperBound(0, (ParameterizedType) returnType);

        final Executor callbackExecutor = retrofit.callbackExecutor();
        if (callbackExecutor == null) throw new AssertionError();

        return new CallAdapter<Object, Call<?>>() {
            @Override
            public Type responseType() {
                return responseType;
            }

            @Override
            public Call<Object> adapt(Call<Object> call) {
                return new ExecutorCallbackCall2<>(callbackExecutor, call);
            }
        };
    }

    static final class ExecutorCallbackCall2<T> implements Call2<T> {
        private final Executor callbackExecutor;
        private final Call<T> delegate;

        /**
         * The executor used for {@link Callback} methods on a {@link Call}. This may be {@code null},
         * in which case callbacks should be made synchronously on the background thread.
         */
        ExecutorCallbackCall2(Executor callbackExecutor, Call<T> delegate) {
            this.callbackExecutor = callbackExecutor;
            this.delegate = delegate;
        }

        @Override
        public void enqueue(final Callback<T> callback) {
            throw new UnsupportedOperationException("please call enqueue(Object tag, Callback2<T> callback2)");
        }

        @Override
        public void enqueue(@Nullable Object tag, final Callback2<T> callback2) {
            Utils.checkNotNull(callback2, "callback2==null");
            CallManager.getInstance().add(this, tag != null ? tag : "NO_TAG");
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (!isCanceled()) {
                        callback2.onStart(ExecutorCallbackCall2.this);
                    }
                }
            });

            delegate.enqueue(new Callback<T>() {
                @Override
                public void onResponse(Call<T> call, final Response<T> response) {
                    callbackExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            callResult(callback2, response, null);
                        }
                    });
                }

                @Override
                public void onFailure(Call<T> call, final Throwable t) {
                    callbackExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            callResult(callback2, null, t);
                        }
                    });
                }
            });
        }

        @UiThread
        private void callResult(Callback2<T> callback2, @Nullable Response<T> response, @Nullable Throwable failureThrowable) {
            try {
                if (!isCanceled()) {
                    //1、获取解析结果
                    Result<T> result;
                    if (response != null) {
                        result = callback2.parseResponse(this, response);
                        Utils.checkNotNull(result, "result==null");
                    } else {
                        Utils.checkNotNull(failureThrowable, "failureThrowable==null");
                        HttpError error = callback2.parseThrowable(this, failureThrowable);
                        result = Result.error(error);
                    }
                    //2、回调成功失败
                    if (result.isSuccess()) {
                        callback2.onSuccess(this, result.body());
                    } else {
                        callback2.onError(this, result.error());
                    }
                }
                callback2.onCompleted(this, failureThrowable, isCanceled());
            } finally {
                CallManager.getInstance().remove(this);
            }
        }

        @Override
        public boolean isExecuted() {
            return delegate.isExecuted();
        }

        @Override
        public Response<T> execute() throws IOException {
            return delegate.execute();
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public boolean isCanceled() {
            return delegate.isCanceled();
        }

        @SuppressWarnings("CloneDoesntCallSuperClone") // Performing deep clone.
        @Override
        public Call2<T> clone() {
            return new ExecutorCallbackCall2<>(callbackExecutor, delegate.clone());
        }

        @Override
        public Request request() {
            return delegate.request();
        }
    }
}
