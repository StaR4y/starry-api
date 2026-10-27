package tech.starryapi.documentation;

/**
 * Published when the set of pages readers can see changes: a publish, re-publish, unpublish or delete. Inside a
 * transaction it is delivered to {@code @TransactionalEventListener(AFTER_COMMIT)} listeners only once the change has
 * committed. Saving a draft does not change what readers see, so it does not publish this event.
 */
public record DocumentationPublicationChanged(String id) {
}
