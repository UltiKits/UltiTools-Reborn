package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * StatefulWidget 是一个具有可变状态的 Widget。
 * <p>
 * 它将 Widget 的配置（由构造函数参数决定）与状态（由 State 对象管理）分离。
 * 当调用 {@link State#setState(VoidCallback)} 时，框架会安排重建，
 * 使用新的 StatefulWidget 实例但保持相同的 State 对象。
 * <p>
 * <b>适用场景：</b>
 * <ul>
 *   <li>需要用户交互的 UI（按钮点击、表单输入）</li>
 *   <li>需要管理内部状态的 UI（展开/折叠、选中状态）</li>
 *   <li>需要订阅外部数据源的 UI（实时数据更新）</li>
 * </ul>
 *
 * <p><strong>生命周期：</strong></p>
 * <ol>
 *   <li>{@link State#initState()} - 初始化状态，只调用一次</li>
 *   <li>{@link State#build(BuildContext)} - 构建 UI，可能调用多次</li>
 *   <li>{@link State#didUpdateWidget(StatefulWidget)} - 配置变化时调用</li>
 *   <li>{@link State#dispose()} - 清理资源，只调用一次</li>
 * </ol>
 *
 * <p><strong>使用示例：</strong></p>
 * <pre>{@code
 * public class CounterButton extends StatefulWidget {
 *     @Override
 *     public State<CounterButton> createState() {
 *         return new CounterState();
 *     }
 * }
 *
 * class CounterState extends State<CounterButton> {
 *     private int count = 0;
 *     
 *     @Override
 *     void initState() {
 *         super.initState();
 *         // 初始化工作
 *     }
 *     
 *     @Override
 *     Widget build(BuildContext context) {
 *         return TextButton.builder()
 *             .text("Clicked: " + count)
 *             .onClick(() -> setState(() -> count++))
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see StatelessWidget
 * @see State
 */
public abstract class StatefulWidget extends Widget {

    /**
     * 创建一个新的 StatefulWidget。
     */
    protected StatefulWidget() {
        super();
    }

    /**
     * 创建一个新的 StatefulWidget，指定 key。
     *
     * @param key 用于稳定标识此 Widget 的键
     */
    protected StatefulWidget(SlotKey key) {
        super(key);
    }

    /**
     * 创建与此 Widget 关联的状态对象。
     * <p>
     * 框架在首次插入 Widget 到树中时调用此方法。
     * 同一个 State 对象会在 Widget 重建时复用。
     *
     * @return 新的 State 实例
     */
    @NotNull
    public abstract State<? extends StatefulWidget> createState();

    @Override
    @NotNull
    public Element createElement() {
        return new StatefulElement(this);
    }
}
