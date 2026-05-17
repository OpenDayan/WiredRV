package xs

package object utils {
  type CircularQueuePtr[T <: queue.CircularQueuePtr[T]] = queue.CircularQueuePtr[T]
  type HasCircularQueuePtrHelper = queue.HasCircularQueuePtrHelper
}
