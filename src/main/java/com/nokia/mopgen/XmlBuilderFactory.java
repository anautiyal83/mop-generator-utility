package com.nokia.mopgen;

/**
 * Factory that instantiates the correct {@link XmlBuilder} implementation
 * based on the name declared in the YAML template ({@code xmlBuilder} key).
 *
 * <h3>Built-in builders</h3>
 * <table border="1">
 *   <tr><th>Name</th><th>Class</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code netconf-soi}</td>
 *     <td>{@link NetconfSoiXmlBuilder}</td>
 *     <td>Nokia SOI NETCONF-over-CLI XML (default)</td>
 *   </tr>
 * </table>
 *
 * <h3>Adding a custom builder</h3>
 * <ol>
 *   <li>Implement {@link XmlBuilder}.</li>
 *   <li>Add a new {@code case} in {@link #create} below.</li>
 *   <li>Declare {@code xmlBuilder: "your-name"} in the YAML template.</li>
 * </ol>
 *
 * No other framework code needs to change.
 */
public class XmlBuilderFactory {

    private XmlBuilderFactory() {}

    /**
     * Create an {@link XmlBuilder} for the given name.
     *
     * @param name    value of the {@code xmlBuilder} YAML key;
     *                {@code null} or empty defaults to {@code "netconf-soi"}
     * @param config  full MOP configuration (passed to implementations that need it)
     * @return the matching builder instance
     * @throws IllegalArgumentException if {@code name} is not a registered builder
     */
    public static XmlBuilder create(String name, MopConfig config) {
        String key = (name == null || name.trim().isEmpty()) ? "netconf-soi" : name.trim();
        switch (key) {
            case "netconf-soi":
                return new NetconfSoiXmlBuilder(config.getNetconfNamespace());
            // ----------------------------------------------------------------
            // Add new cases here to register additional XmlBuilder implementations.
            // Example:
            //   case "ericsson-cm":
            //       return new EricssonCmXmlBuilder(config);
            //   case "rest-json":
            //       return new RestJsonPayloadBuilder(config);
            // ----------------------------------------------------------------
            default:
                throw new IllegalArgumentException(
                    "Unknown xmlBuilder: \"" + key + "\". " +
                    "Available built-in builders: [netconf-soi]. " +
                    "To add a custom builder, implement XmlBuilder and register it in XmlBuilderFactory.");
        }
    }
}
