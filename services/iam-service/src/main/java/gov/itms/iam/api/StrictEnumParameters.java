package gov.itms.iam.api;

import java.beans.PropertyEditorSupport;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Query and path parameters bound to an enum (for example {@code ?status=OPEN}) must be exactly a constant's name
 * (SEC-022). Spring's own enum binding trims the value first, so {@code "OPEN "} or {@code "OPEN\0"} would be accepted.
 * This editor, registered for every enum type, refuses surrounding whitespace and control characters; the exact,
 * case-sensitive name lookup that follows refuses everything else. A refused value is answered 400
 * {@code VALIDATION_FAILED}. An empty parameter still means "not given".
 */
@ControllerAdvice(basePackageClasses = StrictEnumParameters.class)
class StrictEnumParameters {

    @InitBinder
    void strictEnums(WebDataBinder binder) {
        binder.registerCustomEditor(Enum.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (text != null && (!text.equals(text.strip()) || text.chars().anyMatch(Character::isISOControl))) {
                    throw new IllegalArgumentException("not an exact enum name");
                }
                setValue(text);
            }
        });
    }
}
