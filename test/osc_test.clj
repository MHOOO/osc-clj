(ns osc-test
  (:use osc-clj
     clojure.test
     clojure.contrib.seq-utils)
  (:import (java.nio ByteBuffer)))

(def HOST "127.0.0.1")
(def PORT (+ 1000 (rand-int 10000)))

(def STR "test-string")

(deftest osc-msg-test []
  (let [buf (ByteBuffer/allocate 128)
        t-args [(make-array Byte/TYPE 20) 
              42 
              (float 4.2) 
              "qwerasdf" 
              (double 123.23) 
              (long 123412341234)]
        _ (osc-encode-msg buf (apply osc-msg "/asdf" "bifsdh" t-args))
        _ (.position buf 0)
        {:keys [path args] :as msg} (osc-decode-packet buf)
        ]
    (is (= "/asdf" path))
    (is (= (count t-args) (count args)))
    (is (= (ffirst t-args) (ffirst args)))
    (is (= (last (first t-args)) (last (first args))))
    (is (= (next t-args) (next args)))))

(deftest thread-lifetime-test []
  (let [server (osc-server PORT)
        client (osc-client HOST PORT)]
    (osc-close client true)
    (osc-close server true)
    (is (= false (.isAlive (:thread server))))
    (is (= false (.isAlive (:thread client))))))

(defn check-msg [msg path & args]
  (is (not (nil? msg)))
  (let [m-args (:args msg)]
    (is (= (:path msg) path))
    (doseq [i (range (count args))]
      (is (= (nth m-args i) (nth args i))))))

(deftest osc-basic-test []
  (let [server (osc-server PORT)
        client (osc-client HOST PORT)
        flag (atom false)]
    (try
      ;(osc-handle server "/test" (fn [msg] (reset! flag true)))
      ;(osc-send client "/test" "i" 42)
      ;(Thread/sleep 200)
      ;(is (= true @flag))

      (osc-send client "/foo" "i" 42)
      (check-msg (osc-recv server "/foo" 600) "/foo" 42)
      (is (nil? (osc-recv server "/foo" 0)))
      (finally 
        (osc-close server true)
        (osc-close client true)))))

(defn osc-tests []
  (binding [*test-out* *out*]
    (run-tests 'osc-test)))
