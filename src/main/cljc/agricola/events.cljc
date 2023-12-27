(ns agricola.events)

(defmulti handle-event :agricola.event/name)

(defmethod handle-event :agricola.event/end-round
  [event]
  [])

(defmethod handle-event :agricola.event/start-round
  [event]
  [])
