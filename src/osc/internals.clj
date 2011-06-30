(defn- osc-pad
  "Add 0-3 null bytes to make buffer position 32-bit aligned."
  [buf]
  (let [extra (mod (.position buf) 4)]
    (if (pos? extra)
      (.put buf PAD 0 (- 4 extra)))))

(defn- osc-align
  "Jump the current position to a 4 byte boundary for OSC compatible alignment."
  [buf]
  (.position buf (bit-and (bit-not 3) (+ 3 (.position buf)))))

(defn- encode-string [buf s]
  (.put buf (.getBytes s))
  (.put buf (byte 0))
  (osc-pad buf))

(defn- encode-blob [buf b]
  (.putInt buf (count b))
  (.put buf b)
  (osc-pad buf))

(defn- encode-timetag
  ([buf] (encode-timetag buf (osc-now)))
  ([buf timestamp]
     (if (= timestamp OSC-TIMETAG-NOW)
       (doto buf (.putInt 0) (.putInt 1))
       (let [secs (+ (/ timestamp 1000) ; secs since Jan. 1, 1970
                     SEVENTY-YEAR-SECS) ; to Jan. 1, 1900
             fracs (/ (bit-shift-left (long (mod timestamp 1000)) 32)
                      1000)
             tag (bit-or (bit-shift-left (long secs) 32) (long fracs))]
         (.putLong buf (long tag))))))

(defn osc-encode-msg [buf msg]
  (let [{:keys [path type-tag args]} msg]
    (encode-string buf path)
    (encode-string buf (str "," type-tag))
    (doseq [[t arg] (map vector type-tag args)]
      (case t
            \i (.putInt buf (int arg))
            \h (.putLong buf (long arg))
            \f (.putFloat buf (float arg))
            \d (.putDouble buf (double arg))
            \b (encode-blob buf arg)
            \s (encode-string buf arg))
      ))
  buf)

(declare osc-encode-packet)

(defn osc-encode-bundle [buf bundle]
  (encode-string buf "#bundle")
  (encode-timetag buf (:timestamp bundle))
  (doseq [item (:items bundle)]
    ; A bit of a hack...
    ; Write an empty bundle element size into the buffer, then encode
    ; the actual bundle element, and then go back and write the correct
    ; size based on the new buffer position.
    (let [start-pos (.position buf)]
      (.putInt buf (int 0))
      (osc-encode-packet buf item)
      (let [end-pos (.position buf)]
        (.position buf start-pos)
        (.putInt buf (- end-pos start-pos 4))
        (.position buf end-pos))))
  buf)

(defn osc-encode-packet [buf packet]
  (if (osc-msg? packet) (osc-encode-msg buf packet) (osc-encode-bundle buf packet)))

(defn- decode-string [buf]
  (let [start (.position buf)]
    (while (not (zero? (.get buf))) nil)
    (let [end (.position buf)
          len (- end start)
          str-buf (byte-array len)]
      (.position buf start)
      (.get buf str-buf 0 len)
      (osc-align buf)
      (String. str-buf 0 (dec len)))))

(defn- decode-blob [buf]
  (let [size (.getInt buf)
        blob (byte-array size)]
    (.get buf blob 0 size)
    (osc-align buf)
    blob))

(defn- decode-msg
  "Pull data out of the message according to the type tag."
  [buf]
  (let [path (decode-string buf)
        type-tag (decode-string buf)
        args (reduce (fn [mem t]
                       (conj mem
                             (case t
                                   \i (.getInt buf)
                                   \h (.getLong buf)
                                   \f (.getFloat buf)
                                   \d (.getDouble buf)
                                   \b (decode-blob buf)
                                   \s (decode-string buf))))
                     []
                     (rest type-tag))]
    (apply osc-msg path type-tag args)))

(defn- decode-timetag- [buf]
  (let [tag (.getLong buf)
        secs (- (bit-shift-right tag 32) SEVENTY-YEAR-SECS)
        ms-frac (bit-shift-right (* (bit-and tag (bit-shift-left 0xffffffff 32))
                                    1000) 32)]
    (+ (* secs 1000) ; secs as ms
       ms-frac)))

(defn- decode-timetag [buf]
  (let [tag (.getLong buf)]
    (if (= tag OSC-TIMETAG-NOW)
      OSC-TIMETAG-NOW
      (let [secs (- (bit-shift-right tag 32) SEVENTY-YEAR-SECS)
            ms-frac (bit-shift-right (* (bit-and tag (bit-shift-left 0xffffffff 32))
                                        1000) 32)]
        (+ (* secs 1000)                ; secs as ms
           ms-frac)))))

(defn- osc-bundle-buf? [buf]
  (let [start-char (char (.get buf))]
    (.position buf (- (.position buf) 1))
    (= \# start-char)))

(declare osc-decode-packet)

(defn- decode-bundle-items [buf]
  (loop [items []]
    (if (.hasRemaining buf)
      (let [item-size (.getInt buf)
            original-limit (.limit buf)
            item (do (.limit buf (+ (.position buf) item-size)) (osc-decode-packet buf))]
        (.limit buf original-limit)
        (recur (conj items item)))
      items)))

(defn- decode-bundle [buf]
  (decode-string buf) ; #bundle
  (osc-bundle (decode-timetag buf) (decode-bundle-items buf)))

(defn osc-decode-packet
  "Decode an OSC packet buffer into a bundle or message map."
  [buf]
  (if (osc-bundle-buf? buf)
    (decode-bundle buf)
    (decode-msg buf)))

(defn recv-next-packet [chan buf]
  (.clear buf)
  (let [src-addr (.receive chan buf)]
    (when (pos? (.position buf))
      (.flip buf)
      [src-addr (osc-decode-packet buf)])))

(defn- handle-msg [listeners* src msg]
  (let [msg (assoc msg
                   :src-host (.getHostName src)
                   :src-port (.getPort src))]
    (doseq [listener @listeners*]
      (listener msg))))

(defn- handle-bundle [listeners* src bundle]
  (doseq [item (:items bundle)]
    (if (osc-msg? item)
      (handle-msg listeners* src item)
      (handle-bundle listeners* src item))))

(defn- listen-loop [chan buf running? listeners*]
  (try
    (while @running?
      (try
        (let [[src pkt] (recv-next-packet chan buf)]
          (cond
            (osc-bundle? pkt) (handle-bundle listeners* src pkt)
            (osc-msg? pkt)    (handle-msg listeners* src pkt)))
        (catch AsynchronousCloseException e
          (if @running?
            (do
              (print-debug "AsynchronousCloseException in OSC listen-loop...")
              (print-debug (.printStackTrace e)))))
        (catch ClosedChannelException e
          (if @running?
            (do
              (print-debug "ClosedChannelException in OSC listen-loop...")
              (print-debug (.printStackTrace e)))))
        (catch Exception e
          (print-debug "Exception in listen-loop: " e " \nstacktrace: "
                       (.printStackTrace e))
          (throw e))))
  (finally
    (if (.isOpen chan)
      (.close chan)))))

(defn- listener-thread [chan buf running? listeners*]
  (let [t (Thread. #(listen-loop chan buf running? listeners*))]
    (.start t)
    t))

(declare ^{:dynamic true} *osc-handlers*)
(declare ^{:dynamic true} *current-handler*)
(declare ^{:dynamic true} *current-path*)

(defn- msg-handler-dispatcher [handlers]
  (fn [msg]
    (doseq [handler (get @handlers (:path msg))]
      (binding [*osc-handlers* handlers
                *current-handler* handler
                *current-path* (:path msg)]
        (handler msg)))))

; OSC Data Types:
; int => i
;  * 32-bit big-endort an two's complement integer
;
; long => h
;  * 64-bit big-endian two's complement integer
;
; float => f
;  * 32-bit big-endian IEEE 754 floating point number
;
; string => s
;  * A sequence of non-null ASCII characters followed by a null, followed by 0-3 additional null characters to make the total number of bits a multiple of 32.
;
; blob => b
;  * An int32 size count, followed by that many 8-bit bytes of arbitrary binary data, followed by 0-3 additional zero bytes to make the total number of bits a multiple of 32.
;
; OSC-timetag
;  * 64-bit big-endian fixed-point timestamp

; TODO: Figure out how to detect a byte array correctly...
(defn- osc-type-tag
  [args]
  (apply str
    (map #(clojure.contrib.fcase/instance-case %1
            Integer "i"
            Long    "h"
            Float   "f"
            Double  "d"
            (type PAD) "b" ; This is lame... what is a byte array an instance of?
            String  "s")
         args)))

(defn- chan-send [peer send-buf]
  (let [{:keys [chan addr]} peer]
    (.send chan send-buf @addr)))

(def SEND-LOOP-TIMEOUT 100) ; ms

(defn- send-loop [running? send-q send-buf chan]
  (while @running?
    (if-let [res (.poll send-q
                        SEND-LOOP-TIMEOUT
                        TimeUnit/MILLISECONDS)]
      (let [[peer m] res]
        (cond
          (osc-msg? m) (osc-encode-msg send-buf m)
          (osc-bundle? m) (osc-encode-bundle send-buf m))
        (.flip send-buf)
        (try
          ((:send-fn peer) peer send-buf)
          (catch Exception e
            (print-debug "Exception in send-loop: " e  "\nstacktrace: "
                         (.printStackTrace e))
            (throw e)))

        (.clear send-buf))) ; clear resets everything
    ))

(defn- sender-thread [& args]
  (let [t (Thread. #(apply send-loop args))]
    (.start t)
    t))

;    (-> (ByteBuffer/allocate BUFFER-SIZE)
;      (osc-encode-msg msg)
;      (peer-send peer))))
;  (-> (ByteBuffer/allocate BUFFER-SIZE)
;    (osc-encode-bundle bundle)
;    (peer-send peer)))
