package app.pages

import app.api.ApiClient
import app.api.ApiClient.Notebook
import com.raquo.laminar.api.L.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object SyncPage:

  def apply(email: String, onLogout: Observer[Unit]): HtmlElement =
    val notebooksVar = Var(List.empty[Notebook])
    val selectedVar  = Var(Set.empty[String])
    val loadingVar   = Var(true)
    val syncBusyVar  = Var(false)
    val errorVar     = Var(Option.empty[String])
    val successVar   = Var(Option.empty[String])

    val loadNotebooks = EventStream.fromFuture(ApiClient.listNotebooks()).map {
      case Right(nbs) => loadingVar.set(false); notebooksVar.set(nbs)
      case Left(err)  => loadingVar.set(false); errorVar.set(Some(err))
    }

    def doSync(): Unit =
      syncBusyVar.set(true); errorVar.set(None); successVar.set(None)
      ApiClient.sync(selectedVar.now().toList).map {
        case Right(msg) =>
          syncBusyVar.set(false)
          successVar.set(Some(msg))
          selectedVar.set(Set.empty)
        case Left(err)  =>
          syncBusyVar.set(false)
          errorVar.set(Some(err))
      }.recover { case e => syncBusyVar.set(false); errorVar.set(Some(e.getMessage)); () }
      ()

    div(
      cls := "page page--sync",
      loadNotebooks --> Observer.empty,

      // ── Header ────────────────────────────────────────────────────────────
      headerTag(
        cls := "app-header",

        div(
          cls := "app-header__logo",
          div(cls := "app-header__logo-icon", "r2"),
          span("remarkable", b("2"), "notion"),
        ),

        div(
          cls := "app-header__right",
          div(
            cls := "user-badge",
            div(cls := "user-badge__dot"),
            span(email),
          ),
          button(
            cls  := "btn btn--ghost btn--sm",
            tpe  := "button",
            "Sign out",
            onClick --> { _ => ApiClient.logout(); onLogout.onNext(()) },
          ),
        ),
      ),

      // ── Main ──────────────────────────────────────────────────────────────
      mainTag(
        cls := "sync-content",

        // Title
        div(
          cls := "sync-title",
          h2("Your notebooks"),
          p("Select the notebooks you want to sync to Notion."),
        ),

        // Banners
        child.maybe <-- errorVar.signal.map(_.map(msg =>
          div(cls := "banner banner--error", "⚠ ", msg)
        )),
        child.maybe <-- successVar.signal.map(_.map(msg =>
          div(cls := "banner banner--success", "✓ ", msg)
        )),

        // Loading
        child.maybe <-- loadingVar.signal.map(loading =>
          if loading then Some(div(cls := "spinner-wrap", div(cls := "spinner"))) else None
        ),

        // Notebook panel
        child <-- notebooksVar.signal.combineWith(loadingVar.signal).map { (nbs, loading) =>
          if loading then div()
          else if nbs.isEmpty then
            div(
              cls := "empty-state",
              span(cls := "empty-state__icon", "📓"),
              p("No notebooks found on your reMarkable account."),
            )
          else
            val total = nbs.size

            div(
              cls := "notebook-panel",

              // Toolbar
              div(
                cls := "notebook-panel__toolbar",
                label(
                  // custom checkbox state
                  child <-- selectedVar.signal.map { sel =>
                    val cls2 =
                      if sel.size == total then "select-all-check select-all-check--all"
                      else if sel.nonEmpty then "select-all-check select-all-check--some"
                      else "select-all-check"
                    div(cls := cls2)
                  },
                  span("Select all"),
                  span(
                    cls := "notebook-count",
                    child.text <-- selectedVar.signal.map(s =>
                      if s.isEmpty then s"$total notebooks" else s"${s.size} of $total selected"
                    ),
                  ),
                  onClick --> { _ =>
                    if selectedVar.now().size == total then selectedVar.set(Set.empty)
                    else selectedVar.set(nbs.map(_.id).toSet)
                  },
                ),
              ),

              // Groups
              div(
                cls := "notebook-groups",
                nbs
                  .groupBy(nb => if nb.folderPath.isEmpty then "" else nb.folderPath)
                  .toList.sortBy(_._1)
                  .map { (folder, notebooks) =>
                    div(
                      cls := "notebook-group",
                      div(
                        cls := "notebook-group__header",
                        span(cls := "notebook-group__header-icon", if folder.isEmpty then "📂" else "📁"),
                        if folder.isEmpty then "My Notebooks" else folder,
                      ),
                      notebooks.sortBy(_.name).map { nb =>
                        label(
                          cls := (
                            "notebook-row" +
                            (if selectedVar.now().contains(nb.id) then " notebook-row--checked" else "")
                          ),
                          // re-evaluate checked class reactively
                          cls <-- selectedVar.signal.map(sel =>
                            "notebook-row" + (if sel.contains(nb.id) then " notebook-row--checked" else "")
                          ),
                          input(
                            cls  := "checkbox",
                            tpe  := "checkbox",
                            checked <-- selectedVar.signal.map(_.contains(nb.id)),
                            onChange.mapToChecked --> { checked =>
                              if checked then selectedVar.update(_ + nb.id)
                              else selectedVar.update(_ - nb.id)
                            },
                          ),
                          span(cls := "notebook-row__icon", "📄"),
                          span(cls := "notebook-row__name", nb.name),
                          // custom check mark
                          child <-- selectedVar.signal.map(sel =>
                            div(cls := (
                              if sel.contains(nb.id) then
                                "notebook-row__check notebook-row__check--on"
                              else
                                "notebook-row__check"
                            ))
                          ),
                        )
                      },
                    )
                  },
              ),
            )
        },

        // Sync action bar
        child <-- notebooksVar.signal.combineWith(loadingVar.signal, selectedVar.signal, syncBusyVar.signal)
          .map { (nbs, loading, sel, busy) =>
            if loading || nbs.isEmpty then div()
            else
              div(
                cls := "sync-actions",
                div(
                  cls := "sync-actions__info",
                  child.text <-- selectedVar.signal.map { s =>
                    if s.isEmpty then "No notebooks selected"
                    else s"${s.size} notebook${if s.size == 1 then "" else "s"} ready to sync"
                  },
                ),
                button(
                  cls  := "btn btn--primary btn--lg",
                  tpe  := "button",
                  disabled <-- syncBusyVar.signal.combineWith(selectedVar.signal)
                    .map { (busy, sel) => busy || sel.isEmpty },
                  child.text <-- syncBusyVar.signal.map(b =>
                    if b then "Syncing…" else "Sync to Notion →"
                  ),
                  onClick --> { _ => doSync() },
                ),
              )
          },
      ),
    )
