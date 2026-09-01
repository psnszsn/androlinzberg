/* artzig stub of AOSP android.provider.DocumentsProvider: the real base class
 * needs ContentProvider/ContentResolver members (validateIncomingUri, uri
 * permission checks, sort-clause helpers) the hosted seam does not yet carry
 * (android.app/content milestone). Linkage only: Intent imports it; no
 * hosted package ships a documents provider. */
package android.provider;
public abstract class DocumentsProvider extends android.content.ContentProvider {
}
