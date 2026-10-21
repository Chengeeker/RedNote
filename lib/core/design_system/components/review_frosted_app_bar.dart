// ignore_for_file: use_null_aware_elements
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// App bar using an opaque material background.
class ReviewFrostedAppBar extends StatelessWidget
    implements PreferredSizeWidget {
  const ReviewFrostedAppBar({
    super.key,
    this.leading,
    this.automaticallyImplyLeading = true,
    this.title,
    this.actions,
    this.automaticallyImplyActions = true,
    this.flexibleSpace,
    this.bottom,
    this.showBottomBorder = true,
    this.elevation,
    this.scrolledUnderElevation,
    this.notificationPredicate = defaultScrollNotificationPredicate,
    this.shadowColor,
    this.shape,
    this.backgroundColor,
    this.foregroundColor,
    this.iconTheme,
    this.actionsIconTheme,
    this.primary = true,
    this.centerTitle,
    this.excludeHeaderSemantics = false,
    this.titleSpacing,
    this.toolbarOpacity = 1,
    this.bottomOpacity = 1,
    this.toolbarHeight = 52,
    this.leadingWidth,
    this.toolbarTextStyle,
    this.titleTextStyle,
    this.systemOverlayStyle,
    this.useDefaultSemanticsOrder = true,
    this.clipBehavior,
    this.actionsPadding,
    this.animateColor = false,
  });

  final Widget? leading;
  final bool automaticallyImplyLeading;
  final Widget? title;
  final List<Widget>? actions;
  final bool automaticallyImplyActions;
  final Widget? flexibleSpace;
  final PreferredSizeWidget? bottom;
  final bool showBottomBorder;
  final double? elevation;
  final double? scrolledUnderElevation;
  final ScrollNotificationPredicate notificationPredicate;
  final Color? shadowColor;
  final ShapeBorder? shape;
  final Color? backgroundColor;
  final Color? foregroundColor;
  final IconThemeData? iconTheme;
  final IconThemeData? actionsIconTheme;
  final bool primary;
  final bool? centerTitle;
  final bool excludeHeaderSemantics;
  final double? titleSpacing;
  final double toolbarOpacity;
  final double bottomOpacity;
  final double? toolbarHeight;
  final double? leadingWidth;
  final TextStyle? toolbarTextStyle;
  final TextStyle? titleTextStyle;
  final SystemUiOverlayStyle? systemOverlayStyle;
  final bool useDefaultSemanticsOrder;
  final Clip? clipBehavior;
  final EdgeInsetsGeometry? actionsPadding;
  final bool animateColor;

  @override
  Size get preferredSize =>
      AppBar(toolbarHeight: toolbarHeight, bottom: bottom).preferredSize;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final baseColor =
        backgroundColor ??
        theme.appBarTheme.backgroundColor ??
        theme.scaffoldBackgroundColor;

    return AppBar(
      leading: leading,
      automaticallyImplyLeading: automaticallyImplyLeading,
      title: title,
      actions: actions,
      automaticallyImplyActions: automaticallyImplyActions,
      flexibleSpace: DecoratedBox(
        decoration: BoxDecoration(
          color: baseColor,
          border: showBottomBorder
              ? Border(
                  bottom: BorderSide(
                    color: theme.colorScheme.outlineVariant.withValues(
                      alpha: 0.24,
                    ),
                    width: 0.5,
                  ),
                )
              : null,
        ),
        child: flexibleSpace,
      ),
      bottom: bottom,
      forceMaterialTransparency: false,
      elevation: elevation ?? 0,
      scrolledUnderElevation: scrolledUnderElevation ?? 0,
      notificationPredicate: notificationPredicate,
      shadowColor: shadowColor,
      shape: shape,
      backgroundColor: baseColor,
      surfaceTintColor: Colors.transparent,
      foregroundColor: foregroundColor,
      iconTheme: iconTheme,
      actionsIconTheme: actionsIconTheme,
      primary: primary,
      centerTitle: centerTitle,
      excludeHeaderSemantics: excludeHeaderSemantics,
      titleSpacing: titleSpacing,
      toolbarOpacity: toolbarOpacity,
      bottomOpacity: bottomOpacity,
      toolbarHeight: toolbarHeight,
      leadingWidth: leadingWidth,
      toolbarTextStyle: toolbarTextStyle,
      titleTextStyle: titleTextStyle,
      systemOverlayStyle: systemOverlayStyle,
      useDefaultSemanticsOrder: useDefaultSemanticsOrder,
      clipBehavior: clipBehavior,
      actionsPadding: actionsPadding,
      animateColor: animateColor,
    );
  }
}
