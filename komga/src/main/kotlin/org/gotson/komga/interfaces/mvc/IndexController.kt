package org.gotson.komga.interfaces.mvc

import jakarta.servlet.ServletContext
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class IndexController(
  servletContext: ServletContext,
) {
  private val baseUrl: String = "${servletContext.contextPath}/"

  // CUSTOM FORK: '/' must stay the LEGACY UI: it is the only bundle that ships the reader
  // (the new UI has no /book/{id}/read route), and the reader links next-ui opens point here.
  // The new UI remains at /next.
  @GetMapping("/")
  fun index(model: Model): String {
    model.addAttribute("baseUrl", baseUrl)
    return "index"
  }

  @GetMapping("/next")
  fun indexNext(model: Model): String {
    model.addAttribute("baseUrl", baseUrl)
    return "index-next"
  }
}
