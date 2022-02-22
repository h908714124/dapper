package dagger.internal.codegen.base;


import static dagger.internal.codegen.base.Preconditions.checkState;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Note this class is a copy of {@code com.google.common.collect.AbstractIterator} (for dependency
 * reasons).
 */
abstract class AbstractIterator<T> implements Iterator<T> {
  private State state = State.NOT_READY;

  protected AbstractIterator() {
  }

  private enum State {
    READY,
    NOT_READY,
    DONE,
    FAILED,
  }

  private T next;

  protected abstract T computeNext();

  protected final T endOfData() {
    state = State.DONE;
    return null;
  }

  @Override
  public final boolean hasNext() {
    checkState(state != State.FAILED);
    switch (state) {
      case DONE:
        return false;
      case READY:
        return true;
      default:
    }
    return tryToComputeNext();
  }

  private boolean tryToComputeNext() {
    state = State.FAILED; // temporary pessimism
    next = computeNext();
    if (state != State.DONE) {
      state = State.READY;
      return true;
    }
    return false;
  }

  @Override
  public final T next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    state = State.NOT_READY;
    // Safe because hasNext() ensures that tryToComputeNext() has put a T into `next`.
    T result = next;
    next = null;
    return result;
  }

  @Override
  public final void remove() {
    throw new UnsupportedOperationException();
  }
}
