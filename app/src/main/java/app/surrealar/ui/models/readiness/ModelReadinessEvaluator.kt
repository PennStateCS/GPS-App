package app.surrealar.ui.models.readiness

/** The readiness rules. Stub until the evaluator package lands; the UI compiles against this. */
object ModelReadinessEvaluator : ReadinessRules {
    override fun evaluate(facts: ModelFacts): ReadinessReport =
        ReadinessReport(Verdict.READY_WITH_WARNINGS, emptyList(), "Readiness not evaluated yet")
}