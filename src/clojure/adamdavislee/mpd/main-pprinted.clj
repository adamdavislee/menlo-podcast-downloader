(ns
 adamdavislee.mpd.main
 (:require
  [neko.activity :refer [defactivity set-content-view!]]
  [neko.debug :refer [*a]]
  [neko.notify :refer [toast fire notification]]
  [neko.resource :as res]
  [neko.context :refer [get-service]]
  [neko.threading :refer [on-ui]]
  [neko.find-view :refer [find-view]]
  [neko.intent :refer [intent]]
  [clojure.xml :as xml]
  [clojure.string :as str]
  [clojure.java.io :as io]
  [clojure.edn :as edn])
 (:import
  android.widget.EditText
  java.util.concurrent.TimeUnit
  java.util.concurrent.Executors
  java.util.Date
  java.util.Calendar
  android.app.AlarmManager
  android.content.Context
  android.content.Intent
  android.app.PendingIntent
  android.widget.Toast
  android.content.BroadcastReceiver
  java.text.SimpleDateFormat
  android.os.SystemClock
  android.app.IntentService))

(res/import-all)

(defn
 mpddir
 []
 (str
  (android.os.Environment/getExternalStorageDirectory)
  "/menlo-podcast-downloader/"))

(defn
 toast-on-ui
 [& messages]
 (on-ui (toast (apply str messages) :short)))

(defn
 pad-left
 [x n p]
 (str (apply str (repeat (- n (count (str x))) p)) x))

(defn
 filename
 [podcast]
 (str
  (:year (:date podcast))
  "-"
  (pad-left (:month (:date podcast)) 2 0)
  "-"
  (pad-left (:day (:date podcast)) 2 0)
  " - "
  (str/replace (:title podcast) #" \| " " - ")
  ".mp3"))

(defn
 notify-podcast
 [podcast & [string]]
 (on-ui
  (fire
   (keyword (:title podcast))
   (notification
    {:icon R$drawable/splash_droid,
     :ticker-text string,
     :content-title string,
     :content-text (filename podcast),
     :action [:activity "adamdavislee.mpd.Activity"]}))))

(defn
 notify
 [string]
 (on-ui
  (fire
   :a
   (notification
    {:icon R$drawable/splash_droid,
     :ticker-text string,
     :content-title string,
     :action [:activity "adamdavislee.mpd.Activity"]}))))

(defn
 download-podcast
 [podcast]
 (notify-podcast podcast "DOWNLOADING PODCAST")
 (.mkdir (io/file (mpddir)))
 (with-open
  [in
   (io/input-stream (:url podcast))
   out
   (io/output-stream (str (mpddir) "." (filename podcast)))]
  (io/copy in out))
 (.renameTo
  (io/file (str (mpddir) "." (filename podcast)))
  (io/file (str (mpddir) (filename podcast))))
 (notify-podcast podcast "DOWNLOADED PODCAST"))

(defn
 item-to-url
 [item]
 (->>
  item
  :content
  (filter (fn* [p1__10732#] (= (first p1__10732#) [:tag :enclosure])))
  first
  :attrs
  :url))

(defn
 item-to-title
 [item]
 (->>
  item
  :content
  (filter (fn* [p1__10733#] (= (first p1__10733#) [:tag :title])))
  first
  :content
  first))

(defn
 item-to-date
 [item]
 (->>
  item
  :content
  (filter (fn* [p1__10734#] (= (first p1__10734#) [:tag :pubDate])))
  first
  :content
  first))

(def
 parse-month-from-feed
 {"jun" 6,
  "sep" 9,
  "feb" 2,
  "jan" 1,
  "apr" 4,
  "nov" 11,
  "mar" 3,
  "dec" 12,
  "oct" 10,
  "may" 5,
  "aug" 8,
  "jul" 7})

(defn
 parse-date-from-feed
 [date]
 {:day (Integer. (first (re-seq #"\d+" date))),
  :month (parse-month-from-feed
          (apply
           str
           (take 3 (.toLowerCase ((vec (re-seq #"\w+" date)) 2))))),
  :year (Integer. ((vec (re-seq #"\d+" date)) 1))})

(defn
 items
 []
 (as->
  (xml/parse "http://podcast.menlo.church/feed/")
  it
  (it :content)
  (first it)
  (it :content)
  (filter (fn* [p1__10735#] (some #{[:tag :item]} p1__10735#)) it)
  (filter
   (fn* [p1__10736#] (:url p1__10736#))
   (map
    (fn
     [item]
     {:url (item-to-url item),
      :date ((comp parse-date-from-feed item-to-date) item),
      :title (item-to-title item)})
    it))))

(defn
 download-podcasts
 []
 (future
  (notify "LOOKING FOR NEW PODCASTS")
  (let
   [coll
    (doall
     (filter
      (fn
       [parsed-item]
       (not
        (some
         (set [(filename parsed-item)])
         (vec (.list (io/file (mpddir)))))))
      (items)))]
   (notify
    (str
     (if (zero? (count coll)) "NO" (count coll))
     " NEW PODCAST"
     (if-not (= 1 (count coll)) "S")))
   (dorun (map download-podcast coll))
   (cond
    (zero? (count coll))
    nil
    (= 1 (count coll))
    (notify "DOWNLOADED THE PODCAST")
    (= 2 (count coll))
    (notify "DOWNLOADED BOTH PODCASTS")
    :else
    (notify (str "DOWNLOADED ALL " (count coll) " PODCASTS"))))))

(gen-class
 :name
 adamdavislee.mpd.BroadcastReceiver
 :extends
 android.content.BroadcastReceiver
 :prefix
 broadcast-receiver-)

(gen-class
 :name
 adamdavislee.mpd.Service
 :extends
 android.app.IntentService
 :init
 init
 :state
 state
 :constructors
 [[] []]
 :prefix
 service-)

(defn
 broadcast-receiver-onReceive
 [this context intent2]
 (.setInexactRepeating
  (get-service :alarm)
  AlarmManager/ELAPSED_REALTIME
  (+ (SystemClock/elapsedRealtime) (* 60 1000))
  AlarmManager/INTERVAL_HALF_DAY
  (PendingIntent/getService
   context
   0
   (Intent. context (resolve (quote adamdavislee.mpd.Service)))
   0)))

(defn service-init [] [["NameForThread"] "NameForThread"])

(defn service-onHandleIntent [this i] (download-podcasts))

(defactivity
 adamdavislee.mpd.Activity
 :key
 :main
 (onCreate
  [this bundle]
  (.superOnCreate this bundle)
  (.setInexactRepeating
   (get-service :alarm)
   AlarmManager/ELAPSED_REALTIME
   (+ (SystemClock/elapsedRealtime) AlarmManager/INTERVAL_HALF_DAY)
   AlarmManager/INTERVAL_HALF_DAY
   (PendingIntent/getService
    (*a)
    0
    (Intent. (*a) (resolve (quote adamdavislee.mpd.Service)))
    0))
  (on-ui
   (set-content-view!
    (*a)
    [:linear-layout
     {:orientation :vertical,
      :layout-width :fill,
      :layout-height :wrap,
      :gravity :center}
     [:button
      {:text "DOWNLOAD NEW PODCASTS RIGHT NOW",
       :text-size 24,
       :padding 128,
       :on-click (fn
                  [this]
                  (toast-on-ui "SCOURING THE WEB FOR PODCASTS")
                  (download-podcasts))}]]))))
