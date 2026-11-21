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
    return "index"
  }

  @GetMapping(value = ["/next", "/next/**"])
  fun indexNext(model: Model): String {
    model.addAttribute("baseUrl", baseUrl)
    return "index-next"
  }
}
