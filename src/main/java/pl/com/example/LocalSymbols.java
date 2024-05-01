package pl.com.example;

import java.util.HashMap;
import java.util.Map;

public class LocalSymbols {

    private final Map<String, String> symbols;

    public LocalSymbols() {
        this.symbols = new HashMap<>();
    }

    public void addSymbol(String name, String type){

        if(!this.symbols.containsKey(name)){
            this.symbols.put(name, type);
        }
    }

    public String getSymbol(String name){
        return this.symbols.get(name);
    }

    public void clearSymbols(){
        this.symbols.clear();
    }

    public boolean isSymbol(String name){
        return this.symbols.containsKey(name);
    }

}
