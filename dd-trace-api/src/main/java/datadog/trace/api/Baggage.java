package datadog.trace.api;

import java.util.Map;

public interface Baggage {
  /**
   * Get baggage item value from its key.
   *
   * @param key The baggage item key to get the value.
   * @return The baggage item value, <code>null</code> if no baggage with the given key.
   */
  String getItemValue(String key);

  /**
   * Get the baggage items as map.
   *
   * @return An immutable map representing baggage items.
   */
  Map<String, String> getItems();

  /**
   * Create a {@link BaggageBuilder} with all the items of the {@link Baggage} instance.
   *
   * @return A {@link BaggageBuilder} with all the items of this instance.
   */
  BaggageBuilder toBuilder();

  // TODO Javadoc
  interface BaggageBuilder {
    BaggageBuilder put(String key, String value);

    BaggageBuilder remove(String key);

    Baggage build();
  }
}
