import org.springframework.boot.autoconfigure.mongo.MongoProperties;
public class test {
    public static void main(String[] args) {
        System.out.println(MongoProperties.class.getAnnotation(org.springframework.boot.context.properties.ConfigurationProperties.class).prefix());
    }
}
