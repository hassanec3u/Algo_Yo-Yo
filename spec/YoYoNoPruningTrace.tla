---- MODULE YoYoNoPruningTrace ----

EXTENDS TLC, Sequences, SequencesExt, Naturals, FiniteSets, Bags, Json, IOUtils, YoYoNoPruning, TVOperators, TraceSpec

(* Replace Nil constant *)
TraceNil == "null"

(* Replace RM constant *)
TraceRM ==
    ToSet(Config[1].RM)
    \* ToSet(JsonTrace[1].RM)

(* Can be extracted from init *)
TPDefault(varName) ==
    CASE varName = "rmState" -> [r \in RM |-> "working"]
    []  varName = "tmState" -> "init"
    []  varName = "tmPrepared" -> {}
    []  varName = "msgs" -> {}

TPUpdateVariables(t) ==
    /\
        IF "phase" \in DOMAIN t
        THEN phase' = UpdateVariable(rmState, "phase", t)
        ELSE TRUE
    /\
        IF "incoming" \in DOMAIN t
        THEN incoming' = UpdateVariable(tmState, "incoming", t)
        ELSE TRUE
    /\
        IF "outgoing" \in DOMAIN t
        THEN tmPrepared' = UpdateVariable(tmPrepared, "outgoing", t)
        ELSE TRUE
    /\
        IF "mailbox" \in DOMAIN t
        THEN mailbox' = UpdateVariable(msgs, "mailbox", t)
        ELSE TRUE


IsDownSource ==
    /\ IsDownSource("DownSource")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            TMRcvPrepared(logline.event_args[1])
        ELSE
            \E r \in RM : DownSource(r)


IsDownOther ==
    /\ IsEvent("DownOther")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            TMRcvPrepared(logline.event_args[1])
        ELSE
            \E r \in RM : DownOther(r)

IsUpSource ==
    /\ IsEvent("UpSource")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            TMRcvPrepared(logline.event_args[1])
        ELSE
            \E r \in RM : UpSource(r)

IsUpOther ==
    /\ IsEvent("UpOther")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            TMRcvPrepared(logline.event_args[1])
        ELSE
            \E r \in RM : UpOther(r)


TPTraceNext ==
        \/ IsDownSource
        \/ IsDownOther
        \/ IsUpSource
        \/ IsUpOther


(* Eventually composed actions *)
ComposedNext == FALSE

BaseSpec == TPInit /\ [][TPNext \/ ComposedNext]_vars
-----------------------------------------------------------------------------
=============================================================================
