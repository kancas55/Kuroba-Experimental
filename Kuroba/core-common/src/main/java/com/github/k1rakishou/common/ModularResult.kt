package com.github.k1rakishou.common

import com.github.k1rakishou.core_logger.Logger
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.concurrent.Callable
import javax.annotation.CheckReturnValue

@DoNotStrip
sealed class ModularResult<V : Any?> {
  data class Value<V : Any?>(val value: V) : ModularResult<V>()
  data class Error<V : Any?>(val error: Throwable) : ModularResult<V>()

  @CheckReturnValue
  fun onError(func: (Throwable) -> Unit): ModularResult<V> {
    when (this) {
      is Value -> {
        return this
      }
      is Error -> {
        try {
          func(this.error)
          return this
        } catch (error: Throwable) {
          return error(error)
        }
      }
    }
  }

  @CheckReturnValue
  fun onSuccess(func: (V) -> Unit): ModularResult<V> {
    when (this) {
      is Value -> {
        try {
          func(this.value)
          return this
        } catch (error: Throwable) {
          return error(error)
        }
      }
      is Error -> {
        return this
      }
    }
  }

  fun isValue(): Boolean = this is Value
  fun isError(): Boolean = this is Error

  fun valueOrNull(): V? {
    if (this is Value) {
      return value
    }

    return null
  }

  fun unwrapValue(): V {
    if (this is Value) {
      return value
    }

    error("Expected value but actual is error")
  }

  fun errorOrNull(): Throwable? {
    if (this is Error) {
      return error
    }

    return null
  }

  fun unwrapError(): Throwable {
    if (this is Error) {
      return error
    }

    error("Expected error but actual is value")
  }

  @CheckReturnValue
  fun wrapCancellationException(): ModularResult<V> {
    return when (this) {
      is Error -> {
        if (error is CancellationException) {
          return error(error)
        }

        return this
      }
      is Value -> this
    }
  }

  /**
   * If this ModularResult hold an error then it will return it right away, if it hold a value then
   * the lambda will be executed with the value as it's input
   * */
  @CheckReturnValue
  inline fun <T : Any?> mapValue(func: (value: V) -> T): ModularResult<T> {
    return when (this) {
      is Error -> error(error)
      is Value -> Try { func(value) }
    }
  }

  @CheckReturnValue
  inline fun mapValueToUnit(): ModularResult<Unit> {
    return when (this) {
      is Error -> error(error)
      is Value -> value(Unit)
    }
  }

  @CheckReturnValue
  inline fun mapError(mapper: (error: Throwable) -> Throwable): ModularResult<V> {
    when (this) {
      is Error -> {
        return try {
          error(mapper(error))
        } catch (error: Throwable) {
          error(error)
        }
      }
      is Value -> {
        return this
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  inline fun <T : Any?> mapErrorToValue(mapper: (error: Throwable) -> T): T {
    return when (this) {
      is Error -> mapper(error)
      is Value -> value as T
    }
  }

  @CheckReturnValue
  inline fun finally(block: () -> Unit): ModularResult<V> {
    block()
    return this
  }

  fun ignore() {
    // No-op. Just an indicator that we don't care about handling this result. This is just so
    // it's obvious that the original intention was to ignore handling the result not that it
    // was forgotten completely.
  }

  /**
   * This is a handy function for cases when you want to only log the error and the return from
   * the function. In case of a value we won't log anything and will just return the value.
   * */
  inline fun safeUnwrap(handler: (Throwable) -> Nothing): V {
    return when (this) {
      is Error -> handler(error)
      is Value -> value
    }
  }

  fun unwrap(): V {
    return when (this) {
      is Error -> throw error
      is Value -> value
    }
  }

  fun logError(
    tag: String,
    logStacktrace: Boolean = true,
    formatErrorMessage: ((Throwable) -> String)? = null
  ): ModularResult<V> {
    when (this) {
      is Error -> {
        if (logStacktrace) {
          Logger.e(tag, "Unhandled error", this.error)
        } else {
          val message = formatErrorMessage?.invoke(this.error)
            ?: this.error.errorMessageOrClassName()

          Logger.e(tag, "Unhandled error: $message")
        }
      }
      is Value -> {
        // no-op
      }
    }

    return this
  }

  override fun toString(): String {
    return when (this) {
      is Value -> value?.toString() ?: "MR.Value{null}"
      is Error -> String.format(
        Locale.ENGLISH,
        "MR.Error{%s, message: %s}",
        error.javaClass.simpleName,
        error.message ?: "No error message"
      )
    }
  }

  companion object {
    @CheckReturnValue
    @JvmStatic
    fun <V : Any?> value(value: V): ModularResult<V> {
      return Value(value)
    }

    @CheckReturnValue
    @JvmStatic
    fun <V : Any?> error(error: Throwable): ModularResult<V> {
      return Error(error)
    }

    @CheckReturnValue
    @JvmStatic
    @Suppress("FunctionName")
    inline fun <T> Try(func: () -> T): ModularResult<T> {
      return try {
        value(func())
      } catch (error: Throwable) {
        error.rethrowCancellationException()
        error(error)
      }
    }

    @CheckReturnValue
    @JvmStatic
    @Suppress("FunctionName")
    inline fun <T> TryJava(func: Callable<T>): ModularResult<T> {
      return try {
        value(func.call())
      } catch (error: Throwable) {
        error.rethrowCancellationException()
        error(error)
      }
    }

  }
}