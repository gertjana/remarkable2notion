package app.pages

import app.api.ApiClient
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

@js.native
@JSGlobal("google.accounts.id")
object GoogleId extends js.Object:
  def initialize(config: js.Object): Unit = js.native
  def renderButton(parent: dom.Element, options: js.Object): Unit = js.native
  def prompt(): Unit = js.native

object LoginPage:

  def apply(onLoggedIn: Observer[Unit]): HtmlElement =
    val errorVar   = Var(Option.empty[String])
    val loadingVar = Var(false)

    def handleCredential(credential: String): Unit =
      loadingVar.set(true)
      errorVar.set(None)
      ApiClient.googleLogin(credential)
        .map { _ => loadingVar.set(false); onLoggedIn.onNext(()) }
        .recover { case e => loadingVar.set(false); errorVar.set(Some(e.getMessage)); () }
      ()

    div(
      cls := "page page--login",
      div(
        cls := "card",

        // Brand
        div(
          cls := "logo",
          span(cls := "logo__r2n",
            "remarkable",
            span(cls := "logo__pill", "2"),
            "notion",
          ),
        ),
        p(cls := "subtitle",
          "Connect your reMarkable tablet to Notion.", br(),
          "Sign in to get started."
        ),

        div(cls := "divider"),

        // Error
        child.maybe <-- errorVar.signal.map(_.map(msg =>
          div(cls := "banner banner--error", "⚠ ", msg)
        )),

        // Loading
        child.maybe <-- loadingVar.signal.map(b =>
          if b then Some(div(cls := "spinner-wrap", div(cls := "spinner"))) else None
        ),

        // Google button
        div(
          cls    := "google-signin-wrap",
          idAttr := "google-signin-btn",
          onMountCallback { ctx =>
            val clientId = dom.window.asInstanceOf[js.Dynamic]
              .selectDynamic("_googleClientId").toString
            GoogleId.initialize(js.Dynamic.literal(
              client_id = clientId,
              callback  = (resp: js.Dynamic) => handleCredential(resp.credential.toString),
            ))
            GoogleId.renderButton(ctx.thisNode.ref, js.Dynamic.literal(
              theme = "outline", size = "large", text = "signin_with",
              shape = "rectangular", width = "300",
            ))
          },
        ),
      ),
    )
