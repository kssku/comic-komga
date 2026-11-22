package org.gotson.komga.interfaces.mvc

import jakarta.servlet.ServletContext
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class IndexController(
  servletContext: ServletContext,
  val komgaSettingsProvider: KomgaSettingsProvider,
) {
  private val baseUrl: String = "${servletContext.contextPath}/"

  // CUSTOM FORK: '/' must stay the LEGACY UI: it is the only bundle that ships the reader
  // (the new UI has no /book/{id}/read route), and the reader links next-ui opens point here.
  // The new UI remains at /next.
  @GetMapping("/")
  fun index(
    model: Model,
  ): String {
    model.addAttribute("baseUrl", baseUrl)

    return if (komgaSettingsProvider.nextUiAsDefault) "index-next" else "index"
  }

  @GetMapping(value = ["/classic", "/classic/**"])
  fun indexClassic(model: Model): String {
    model.addAttribute("baseUrl", baseUrl)
    // CUSTOM FORK (fix 404): the classic bundle is a Vue 2 SPA whose router base is "/", so serving
    // it for "/classic/**" left every deep link matching no route — and /classic/book/{id}/read is
    // exactly what the new UI links to (see next-ui/src/api/links.ts). This template strips the
    // "/classic" prefix from the address bar before the app boots, so the router resolves the real
    // path. It is otherwise identical to "index".
    return "index-classic"
  }

  @GetMapping(value = ["/next", "/next/**"])
  fun indexNext(model: Model): String {
    model.addAttribute("baseUrl", baseUrl)
    return "index-next"
  }
}
