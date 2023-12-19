(ns agicola.views.humbleui
  (:require
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.types IPoint]))

(def ui
  (let [gap-size 40]
    (ui/default-theme
     {}
     (ui/draggable
      {:on-dragging (fn [event] (println "dragging:" ((juxt :x :y) event)))
       :on-drop (fn [event] (println "dropped:" event))
       :pos (IPoint. 300 300)}
      (ui/label "Click me!"))
     #_(ui/column
        (interpose
         (ui/gap gap-size gap-size)
         (for [_ (range 2)]
           (ui/center
            (ui/row
             (interpose
              (ui/gap gap-size gap-size)
              (for [i (range 3)]
                (ui/column
                 (interpose (ui/gap gap-size gap-size)
                            (for [j (range 3)]
                              (ui/button (fn [] (println "I'm clicked!"))
                                         (ui/draggable (ui/label "Click me!")))))))

              #_(for [i (range 3)]
                  (interpose (ui/gap 10 10)
                             (for [j (range 3)]
                               (ui/center
                                (ui/button (fn [& args] (println "I'm clicked!"))
                                           (ui/label "Click me!")))))))))))))))

(ui/start-app!
 (ui/window
  {:title "Humble 🐝 UI"}
  #'ui))
