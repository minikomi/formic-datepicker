(ns formic.components.date-picker
  (:require [cljs-time.core :as t]
            [cljs-time.coerce :refer [from-long]]
            [cljs-time.format :refer [formatter parse unparse]]
            [formic.util :as u]
            [formic.field :as field]
            [reagent.core :as r]
            [goog.events :as events]
            [goog.events.EventType :as event-type]
            [goog.dom :as gdom]))

;; constants ------------------------

(def MONTHS ["Jan" "Feb" "Mar"
             "Apr" "May" "Jun"
             "Jul" "Aug" "Sep"
             "Oct" "Nov" "Dec"])

(def DAYS ["Su" "Mo" "Tu" "We" "Th" "Fr" "Sa"])

(def MIN_DATE (from-long -8640000000000000))
(def MAX_DATE (from-long 8640000000000000))

(defn today? [d] (and
                  (= (t/year (t/today))) (t/year d)
                  (= (t/month (t/today)) (t/month d))
                  (= (t/day (t/today)) (t/day d))))

(def DEFAULT_FORMATTER (formatter "YYYY-MM-dd"))

(defn DEFAULT_SERIALIZER [d]
  (unparse DEFAULT_FORMATTER d))

(defn DEFAULT_PARSER [d]
  (parse DEFAULT_FORMATTER d))

(defn DEFAULT_STRINGIFY [d]
  (if d
    (str (DEFAULT_SERIALIZER d)
         " (" (get DAYS (mod (t/day-of-week d) 7)) ")")
    ""))

;;prev

(defn prev-month [month]
  (t/minus month (t/months 1)))

(defn prev-active? [valid-date-range month]
  (t/within? valid-date-range
             (t/last-day-of-the-month
              (prev-month month))))

;;next

(defn next-month [month]
  (t/plus month (t/months 1)))

(defn next-active? [valid-date-range month]
  (t/within? valid-date-range
             (t/first-day-of-the-month
              (next-month month))))

(defn valid-date? [valid-date-range d]
  (t/within? valid-date-range d))

(defn week->row [week shown-month date valid-date-range active? is-open on-selected]
  (into [:tr {:key week}]
        (for [d week]
          (if (nil? d)
            [:td.empty]
            (let [this-day (t/local-date-time (t/year @shown-month)
                                              (t/month @shown-month)
                                              d)
                  is-valid (and
                            (valid-date? valid-date-range this-day)
                            (active? this-day))
                  is-selected (t/= date this-day)
                  is-today (today? this-day)
                  click-handler (fn [ev]
                                  (.preventDefault ev)
                                  (when is-valid
                                    (reset! is-open false)
                                    (on-selected this-day)))]
              [:td
               {:key [week d]
                :class (str
                        (if is-valid "valid" "not-valid")
                        (when is-today " today")
                        (when is-selected " selected"))
                :on-click click-handler
                :on-touch-start click-handler}
               [:span.wrapper d]])))))

(defn gen-valid-months [min-date max-date year]
  (for [m (range 12)
        :let [frst (t/local-date-time year (inc m) 1)
              last (t/last-day-of-the-month frst)]
        :when (t/overlaps?
               frst last
               min-date max-date)] m))

(defn picker-nav [min-date
                  max-date
                  prev-active
                  next-active
                  prev-handler
                  next-handler
                  shown-month]
  (let [select-min-year (t/year
                         (t/latest
                          min-date
                          (t/minus (t/today) (t/years 100))))
        select-max-year (t/year
                         (t/earliest
                          max-date
                          (t/plus (t/today) (t/years 100))))
        select-years    (into (vec (range select-min-year (t/year @shown-month)))
                              (vec (range (t/year @shown-month) (inc select-max-year))))
        select-months   (gen-valid-months min-date max-date (t/year @shown-month))]
    [:tr.navigation
     [:td {:class    (str "prev" (when prev-active) " active")
           :on-click prev-handler} "<"]
     [:td {:col-span 5}
      [:select
       {:value (str (t/month @shown-month))
        :on-change
        (fn [e]
          (let [v (js/parseInt (.. e -target -value) 10)]
            (reset! shown-month
                    (t/local-date-time (t/year @shown-month)
                                 v
                                 1))))}
       (for [m select-months]
         ^{:key (str "select-month-" m)}
         [:option {:value (inc m)} (get MONTHS m)])]
      [:select
       {:value     (str (t/year @shown-month))
        :on-change (fn [e]
                     (let [v             (js/parseInt (.. e -target -value) 10)
                           valid-months  (gen-valid-months min-date max-date v)
                           current-month (t/month @shown-month)]
                       (reset! shown-month
                               (t/local-date-time
                                v
                                (if ((set valid-months) current-month)
                                  current-month
                                  (inc (first valid-months)))
                                1))))}

       (for [y select-years]
         ^{:key (str "select-year" y)}
         [:option {:value y} y])]]
     [:td {:class    (str "next" (when next-active) " active")
           :on-click next-handler} ">"]]))

(defn picker-table [{:keys [date
                            months
                            days
                            min-date
                            max-date
                            is-open
                            active?
                            table-id
                            on-selected] :as props}]
  (let [shown-month (r/atom
                     (if date
                       (t/first-day-of-the-month date)
                       (t/first-day-of-the-month
                        (t/earliest
                         max-date
                         (t/latest (t/today) min-date)))))
        valid-date-range (t/interval min-date max-date)
        ;; prev / next
        prev-active (prev-active? valid-date-range @shown-month)
        next-active (next-active? valid-date-range @shown-month)
        prev-handler (fn [ev]
                       (.preventDefault ev)
                       (when (prev-active? valid-date-range @shown-month)
                         (swap! shown-month prev-month)))
        next-handler (fn [ev]
                       (.preventDefault ev)
                       (when (next-active? valid-date-range @shown-month)
                         (swap! shown-month next-month)))
        ;; handler to hide on outside click
        outside-click-handler (fn [ev]
                                (when (and (gdom/getElement table-id)
                                           (not (.contains
                                                 (gdom/getElement table-id)
                                                 (.-target ev))))
                                  (reset! is-open false)))
        ;; keydown handler
        key-down-handler (fn [ev]
                           (case (.-keyCode ev)
                             37 (prev-handler ev)
                             39 (next-handler ev)
                             27 (do
                                  (.preventDefault ev)
                                  (reset! is-open false))
                             ;; default
                             true))]
    (r/create-class
     {:reagent-render
      (fn [{:keys [date is-open] :as props}]
        (let [num-days    (t/number-of-days-in-the-month @shown-month)
              first-day-n (t/day-of-week @shown-month)
              month-days  (concat
                           (when (< first-day-n 7)
                             (take first-day-n (repeat nil)))
                           (map inc (take num-days (range)))
                           (take (mod (- 7 (mod (+ first-day-n num-days) 7)) 7)
                                 (repeat nil)))
              weeks       (partition-all 7 month-days)]
          [:div.date-picker-table-wrapper
           [:table.date-picker-table
            {:id table-id}
            (into
             [:tbody
              ;; nav
              [picker-nav min-date max-date prev-active
               next-active prev-handler next-handler shown-month]
              ;; days headers
              (into [:tr.days]
                    (for [d DAYS]
                      ^{:key d}
                      [:td {:key (str "week-day-" d)} d]))
              ;; weeks table
              (doall
               (for [w weeks]
                 (week->row w
                            shown-month
                            date
                            valid-date-range
                            active?
                            is-open
                            on-selected)))])]]))
      :component-did-mount
      (fn [this]
        (events/listen
         js/window
         event-type/CLICK
         outside-click-handler)
        (events/listen
         js/window
         event-type/KEYDOWN
         key-down-handler))
      :component-will-unmount
      (fn [this]
        (events/unlisten
         js/window
         event-type/CLICK
         outside-click-handler)
        (events/unlisten
         js/window
         event-type/KEYDOWN
         key-down-handler))})))

(defn date-picker [f]
  (let [is-open (r/atom false)
        {:keys [label
                id
                min-date
                max-date
                stringify
                months
                touched
                days
                err
                active?
                current-value]} f]
    (fn [{:keys [value]}]
      [:div.date-picker
       [:label
        [:span.formic-input-title (or label (u/format-kw id))]
        [:input.append
         {:read-only true
          :value ((or stringify DEFAULT_STRINGIFY) @current-value)
          :type "text"
          :on-click
          (fn handle-click [ev]
            (.preventDefault ev)
            (reset! touched true)
            (swap! is-open not))}]]
       [:span.input-inline.append
        {:on-click
         (fn handle-click [ev]
           (.preventDefault ev)
           (swap! is-open not))}]
       (when @is-open
         [picker-table {:is-open is-open
                        :months (or months MONTHS)
                        :days (or days DAYS)
                        :min-date (or min-date MIN_DATE)
                        :max-date (or max-date MAX_DATE)
                        :active? (or active? (constantly true))
                        :table-id (str (name (:id f)) "-table")
                        :date @current-value
                        :on-selected #(reset! current-value %)}])
       (when-let [err @(:err f)]
         [:h3.error err])])))

(field/register-component
 :formic-datepicker
 {:component date-picker
  :parser DEFAULT_PARSER
  :serializer DEFAULT_SERIALIZER})
