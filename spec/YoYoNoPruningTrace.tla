---- MODULE YoYoNoPruningTrace ----

EXTENDS TLC, Sequences, SequencesExt, Naturals, Bags, Json, IOUtils, YoYoNoPruning, TVOperators, TraceSpec

(* Replace Nil constant *)
TraceNil == "null"

(* Replace RM constant *)
TraceNodes ==
    {1, 2, 3, 4, 5}
    \*ToSet(Config[1].Nodes)
    \* ToSet(JsonTrace[1].RM)

TraceEdges ==
    {{1, 3}, {2, 3}, {3, 4}, {3, 5}}
    \* ToSet(JsonTrace[1].RM)
(* Can be extracted from init *)
TPDefault(varName) ==
    CASE varName = "phase" -> [n \in Nodes |-> "down"]
    []  varName = "incoming" -> [n \in Nodes |-> { m \in Neighbors(n) : m < n}]
    []  varName = "outgoing" -> [n \in Nodes |-> { m \in Neighbors(n) : m > n}]
    []  varName = "mailbox" -> [n \in Nodes |-> {}]

TPUpdateVariables(t) ==
    /\
        IF "phase" \in DOMAIN t
        THEN phase' = UpdateVariable(phase, "phase", t)
        ELSE TRUE
    /\
        IF "incoming" \in DOMAIN t
        THEN incoming' = UpdateVariable(incoming, "incoming", t)
        ELSE TRUE
    /\
        IF "outgoing" \in DOMAIN t
        THEN outgoing' = UpdateVariable(outgoing, "outgoing", t)
        ELSE TRUE
    /\
        IF "mailbox" \in DOMAIN t
        THEN mailbox' = UpdateVariable(mailbox, "mailbox", t)
        ELSE TRUE


IsDownSource ==
    /\ IsEvent("DownSource")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            DownSource(logline.event_args[1])
        ELSE
            \E r \in Nodes : DownSource(r)

IsDownOther ==
    /\ IsEvent("DownOther")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            DownOther(logline.event_args[1])
        ELSE
            \E r \in Nodes : DownOther(r)

IsUpSource ==
    /\ IsEvent("UpSource")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            UpSource(logline.event_args[1])
        ELSE
            \E r \in Nodes : UpSource(r)

IsUpOther ==
    /\ IsEvent("UpOther")
    /\
        IF "event_args" \in DOMAIN logline /\ Len(logline.event_args) >= 1 THEN
            UpOther(logline.event_args[1])
        ELSE
            \E r \in Nodes : UpOther(r)


TPTraceNext ==
        \/ IsDownSource
        \/ IsDownOther
        \/ IsUpSource
        \/ IsUpOther


(* Eventually composed actions *)
ComposedNext == FALSE

BaseSpec == Init /\ [][Next \/ ComposedNext]_vars
-----------------------------------------------------------------------------
=============================================================================