package app

import app.api.ApiClient
import app.pages.{LoginPage, SetupPage, SyncPage}
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** Application entry point and top-level router.
  *
  * Routing is purely in-memory (no URL change) — the app is a single page with
  * three states:
  *   - Loading  : checking session via GET /api/me
  *   - Login    : not authenticated
  *   - Setup    : authenticated but not fully configured (reMarkable / Notion)
  *   - Sync     : fully configured, show notebook list
  */
object Main:

  def main(args: Array[String]): Unit =
    val appContainer = dom.document.getElementById("app")
    renderOnDomContentLoaded(appContainer, app)

  // ---------------------------------------------------------------------------
  // App states
  // ---------------------------------------------------------------------------

  enum AppState:
    case Loading
    case Login
    case Setup(step: Int)           // 0 = pair reMarkable, 1 = add Notion key
    case Sync(email: String)

  // ---------------------------------------------------------------------------
  // Root element
  // ---------------------------------------------------------------------------

  private val devMode: Boolean =
    dom.window.asInstanceOf[js.Dynamic].selectDynamic("_devMode").asInstanceOf[Boolean]

  private def app: HtmlElement =
    val stateVar = Var[AppState](AppState.Loading)

    // On boot, check /api/me — in dev mode the backend always returns the dev user.
    // In prod mode a missing/invalid cookie will return a Left, sending us to Login.
    val checkSession = EventStream
      .fromFuture(ApiClient.me())
      .map {
        case Right(me) =>
          val step = if !me.remarkablePaired then 0 else if !me.notionConfigured then 1 else -1
          if step >= 0 then stateVar.set(AppState.Setup(step))
          else stateVar.set(AppState.Sync(me.email))
        case Left(_) =>
          if devMode then stateVar.set(AppState.Setup(0))
          else stateVar.set(AppState.Login)
      }

    div(
      cls := "root",

      // Kick off the session check
      checkSession --> Observer.empty,

      child <-- stateVar.signal.map {

        case AppState.Loading =>
          div(cls := "loading-screen", div(cls := "spinner"))

        case AppState.Login =>
          LoginPage(
            onLoggedIn = Observer(_ =>
              // After login re-check me to know which setup step is needed
              ApiClient.me().map {
                case Right(me) =>
                  val step = if !me.remarkablePaired then 0 else if !me.notionConfigured then 1 else -1
                  if step >= 0 then stateVar.set(AppState.Setup(step))
                  else stateVar.set(AppState.Sync(me.email))
                case Left(_) => stateVar.set(AppState.Login)
              }
              ()
            ),
          )

        case AppState.Setup(step) =>
          SetupPage(
            initialStep = step,
            onComplete = Observer(_ =>
              ApiClient.me().map {
                case Right(me) =>
                  if !me.remarkablePaired then stateVar.set(AppState.Setup(0))
                  else if !me.notionConfigured then stateVar.set(AppState.Setup(1))
                  else stateVar.set(AppState.Sync(me.email))
                case Left(_) => stateVar.set(AppState.Login)
              }
              ()
            ),
          )

        case AppState.Sync(email) =>
          SyncPage(
            email    = email,
            onLogout = Observer { _ =>
              if devMode then stateVar.set(AppState.Setup(0))
              else stateVar.set(AppState.Login)
            },
          )
      },
    )
