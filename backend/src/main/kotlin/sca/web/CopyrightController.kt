package sca.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import sca.acquire.InvalidInput
import sca.product.CopyrightStore

data class CopyrightEdit(val key: String, val text: String)

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/copyrights")
class CopyrightController(private val copyrights: CopyrightStore) {
    /** The key travels in the body: component keys contain '/' and '@'. */
    @PutMapping fun set(@RequestBody req: CopyrightEdit): ResponseEntity<Unit> = ResponseEntity.noContent().build<Unit>().also { copyrights.set(req.key, req.text) }

    @ExceptionHandler(InvalidInput::class) fun invalid(e: InvalidInput) = ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
