package app.pages

import app.api.ApiClient
import com.raquo.laminar.api.L.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object SetupPage:

  def apply(initialStep: Int, onComplete: Observer[Unit]): HtmlElement =
    val stepVar  = Var(initialStep)
    val errorVar = Var(Option.empty[String])
    val busyVar  = Var(false)

    div(
      cls := "page page--setup",
      child <-- stepVar.signal.map {
        case 0 => reMarkableStep(stepVar, errorVar, busyVar)
        case _ => notionStep(errorVar, busyVar, onComplete)
      },
    )

  // ---------------------------------------------------------------------------
  // Step 1 — reMarkable pairing
  // ---------------------------------------------------------------------------
  private def reMarkableStep(
    stepVar:  Var[Int],
    errorVar: Var[Option[String]],
    busyVar:  Var[Boolean],
  ): HtmlElement =
    val codeVar = Var("")

    div(
      cls := "card",

      stepIndicator(current = 0),

      div(
        cls := "setup-header",
        h2("Connect reMarkable"),
        p("Generate a one-time code on the reMarkable website and paste it below."),
      ),

      div(
        cls := "info-box",
        "Visit ",
        a(
          href   := "https://my.remarkable.com/device/desktop/connect",
          target := "_blank",
          rel    := "noopener noreferrer",
          "my.remarkable.com/device/desktop/connect",
        ),
        ", sign in, and copy the code shown on screen.",
      ),

      child.maybe <-- errorVar.signal.map(_.map(msg =>
        div(cls := "banner banner--error", "⚠ ", msg)
      )),

      div(
        cls := "form-group",
        label(cls := "form-label", forId := "rm-code", "One-time pairing code"),
        input(
          cls         := "input input--mono",
          idAttr      := "rm-code",
          tpe         := "text",
          placeholder := "e.g. ABCD1234",
          maxLength   := 12,
          autoComplete := "off",
          spellCheck  := false,
          controlled(value <-- codeVar.signal, onInput.mapToValue --> codeVar),
        ),
      ),

      button(
        cls  := "btn btn--primary btn--lg",
        tpe  := "button",
        disabled <-- busyVar.signal,
        child.text <-- busyVar.signal.map(b => if b then "Pairing…" else "Pair device →"),
        onClick --> { _ =>
          busyVar.set(true); errorVar.set(None)
          ApiClient.pairRemarkable(codeVar.now()).map {
            case Right(_)  => busyVar.set(false); stepVar.set(1)
            case Left(err) => busyVar.set(false); errorVar.set(Some(err))
          }.recover { case e => busyVar.set(false); errorVar.set(Some(e.getMessage)); () }
          ()
        },
      ),
    )

  // ---------------------------------------------------------------------------
  // Step 2 — Notion key
  // ---------------------------------------------------------------------------
  private def notionStep(
    errorVar:   Var[Option[String]],
    busyVar:    Var[Boolean],
    onComplete: Observer[Unit],
  ): HtmlElement =
    val keyVar = Var("")

    div(
      cls := "card",

      stepIndicator(current = 1),

      div(
        cls := "setup-header",
        h2("Connect Notion"),
        p("Paste your Notion Internal Integration Secret to enable syncing."),
      ),

      div(
        cls := "info-box",
        "Create an integration at ",
        a(
          href   := "https://www.notion.so/my-integrations",
          target := "_blank",
          rel    := "noopener noreferrer",
          "notion.so/my-integrations",
        ),
        " and copy the Internal Integration Secret (starts with ", code("secret_"), ").",
      ),

      child.maybe <-- errorVar.signal.map(_.map(msg =>
        div(cls := "banner banner--error", "⚠ ", msg)
      )),

      div(
        cls := "form-group",
        label(cls := "form-label", forId := "notion-key", "Integration secret"),
        input(
          cls         := "input input--mono",
          idAttr      := "notion-key",
          tpe         := "password",
          placeholder := "secret_...",
          autoComplete := "off",
          controlled(value <-- keyVar.signal, onInput.mapToValue --> keyVar),
        ),
      ),

      button(
        cls  := "btn btn--primary btn--lg",
        tpe  := "button",
        disabled <-- busyVar.signal,
        child.text <-- busyVar.signal.map(b => if b then "Saving…" else "Save & continue →"),
        onClick --> { _ =>
          busyVar.set(true); errorVar.set(None)
          ApiClient.saveNotionKey(keyVar.now()).map {
            case Right(_)  => busyVar.set(false); onComplete.onNext(())
            case Left(err) => busyVar.set(false); errorVar.set(Some(err))
          }.recover { case e => busyVar.set(false); errorVar.set(Some(e.getMessage)); () }
          ()
        },
      ),
    )

  // ---------------------------------------------------------------------------
  // Step indicator
  // ---------------------------------------------------------------------------
  private val steps = List("reMarkable", "Notion")

  private def stepIndicator(current: Int): HtmlElement =
    div(
      cls := "steps",
      steps.zipWithIndex.flatMap { (label, i) =>
        val circleClass =
          if i < current then "step-circle step-circle--done"
          else if i == current then "step-circle step-circle--active"
          else "step-circle"
        val labelClass =
          if i == current then "step-label step-label--active" else "step-label"
        val lineClass =
          if i < current then "step-line step-line--done" else "step-line"

        val item = div(
          cls := "step-item",
          div(cls := circleClass,
            if i >= current then span(s"${i + 1}") else emptyNode,
          ),
          span(cls := labelClass, label),
          if i < steps.length - 1 then div(cls := lineClass) else emptyNode,
        )
        List(item)
      },
    )
