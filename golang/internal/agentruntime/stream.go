// Agent 事件缓冲队列与 run 结果汇总
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package agentruntime

import (
	"context"
	"iter"
	"sync"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// EventQueue 生产者与消费者之间的事件缓冲，超过上限时丢弃最早的 raw_vendor_event。
type EventQueue struct {
	mutex       sync.Mutex
	events      []core.AgentEvent
	notify      chan struct{}
	isClosed    bool
	maxBuffered int
}

// NewEventQueue 创建事件队列。
func NewEventQueue(maxBuffered int) *EventQueue {
	return &EventQueue{
		notify:      make(chan struct{}, 1),
		maxBuffered: maxBuffered,
	}
}

// Push 追加事件，超过上限时丢弃最早的 raw_vendor_event，标准事件不丢弃。
func (q *EventQueue) Push(event core.AgentEvent) {
	q.mutex.Lock()
	q.events = append(q.events, event)

	if q.maxBuffered > 0 && len(q.events) > q.maxBuffered {
		q.dropOldestRawEvent()
	}

	q.mutex.Unlock()
	q.signal()
}

// dropOldestRawEvent 删除队列中最早的 raw_vendor_event
func (q *EventQueue) dropOldestRawEvent() {
	for index, event := range q.events {
		if event.Type != core.AgentEventRawVendor {
			continue
		}

		q.events = append(q.events[:index], q.events[index+1:]...)
		return
	}
}

// Close 标记生产结束。
func (q *EventQueue) Close() {
	q.mutex.Lock()
	q.isClosed = true
	q.mutex.Unlock()
	q.signal()
}

// signal 唤醒等待中的消费者
func (q *EventQueue) signal() {
	select {
	case q.notify <- struct{}{}:
	default:
	}
}

// Pop 取出最早的事件，队列关闭且为空时返回 false。
func (q *EventQueue) Pop() (core.AgentEvent, bool) {
	for {
		q.mutex.Lock()

		if len(q.events) > 0 {
			event := q.events[0]
			q.events = q.events[1:]
			q.mutex.Unlock()

			return event, true
		}

		if q.isClosed {
			q.mutex.Unlock()
			return core.AgentEvent{}, false
		}

		q.mutex.Unlock()
		<-q.notify
	}
}

// IterateEvents 把队列转换为迭代器，调用方提前停止迭代时调用 stop 结束 run。
func IterateEvents(queue *EventQueue, stop func()) iter.Seq[core.AgentEvent] {
	return func(yield func(core.AgentEvent) bool) {
		for {
			event, hasEvent := queue.Pop()
			if !hasEvent {
				return
			}

			if !yield(event) {
				stop()
				return
			}

			if event.IsTerminal() {
				return
			}
		}
	}
}

// CollectResult 消费全部事件并返回终止事件携带的 AgentResult，collectEvents 为 true 时结果附带全部事件。
func CollectResult(events iter.Seq[core.AgentEvent], collectEvents bool, agent string) *core.AgentResult {
	collected := []core.AgentEvent{}
	var result *core.AgentResult

	for event := range events {
		if collectEvents {
			collected = append(collected, event)
		}

		if event.IsTerminal() {
			result = event.Result
		}
	}

	if result == nil {
		info := core.NewError(core.CategoryInternalError, "run 结束但没有终止事件", agent).Info
		return &core.AgentResult{
			Status: core.AgentStatusFailed,
			Error:  &info,
		}
	}

	copied := *result
	if collectEvents {
		copied.Events = collected
	}

	return &copied
}

// WaitForContext 返回 ctx 结束的原因：超时为 TIMEOUT，其余为 CANCELLED。
func WaitForContext(ctx context.Context, provider string) *core.Error {
	if ctx.Err() == context.DeadlineExceeded {
		return core.WrapError(core.CategoryTimeout, "run 超过 timeout_seconds 上限", provider, ctx.Err())
	}

	return core.WrapError(core.CategoryCancelled, "run 已取消", provider, ctx.Err())
}
