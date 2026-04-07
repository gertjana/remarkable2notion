package app.components

import com.raquo.laminar.api.L.*

/** Shared UI components. */
object Components:

  def spinner: HtmlElement =
    div(cls := "spinner")

  def errorBanner(msg: String): HtmlElement =
    div(cls := "banner banner--error", msg)

  def successBanner(msg: String): HtmlElement =
    div(cls := "banner banner--success", msg)

  def primaryButton(label: String, isDisabled: Signal[Boolean] = Signal.fromValue(false)): HtmlElement =
    button(
      cls  := "btn btn--primary",
      tpe  := "button",
      disabled <-- isDisabled,
      label,
    )

  def inputField(
    placeholderText: String,
    inputType: String = "text",
    valueObserver: Observer[String],
  ): HtmlElement =
    input(
      cls         := "input",
      tpe         := inputType,
      placeholder := placeholderText,
      onInput.mapToValue --> valueObserver,
    )
