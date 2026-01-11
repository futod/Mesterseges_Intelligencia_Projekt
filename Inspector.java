import java.lang.reflect.Field;
import java.lang.reflect.Method;
import game.mario.utils.MarioState;
import game.mario.MarioGame;

public class Inspector {
    public static void main(String[] args) {
        System.out.println("Declared Fields of game.mario.utils.MarioState:");
        for (Field f : MarioState.class.getDeclaredFields()) {
            f.setAccessible(true);
            System.out.println("  " + f.getName() + " (" + f.getType().getName() + ")");
        }
    }

    static String getVal(Field f) {
        try {
            return f.get(null).toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
